package com.newscurator.service;

import com.newscurator.domain.Account;
import com.newscurator.domain.ConsentRecord;
import com.newscurator.domain.TermsVersion;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.ConsentInput;
import com.newscurator.dto.request.LoginRequest;
import com.newscurator.dto.request.SignupRequest;
import com.newscurator.dto.response.AccountSummaryResponse;
import com.newscurator.dto.response.SignupResponse;
import com.newscurator.dto.response.TokenPairResponse;
import com.newscurator.security.JwtTokenProvider;
import com.newscurator.exception.AccountSuspendedException;
import com.newscurator.exception.EmailAlreadyExistsException;
import com.newscurator.exception.EmailDeliveryException;
import com.newscurator.exception.SocialOnlyAccountException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ConsentRecordRepository;
import com.newscurator.repository.TermsVersionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern PASSWORD_POLICY =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");
    private static final int MAX_FAILED = 5;
    private static final long LOCK_MINUTES = 30L;

    private final AccountRepository accountRepository;
    private final TermsVersionRepository termsVersionRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final EmailVerificationService emailVerificationService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TransactionTemplate transactionTemplate;

    public AuthService(AccountRepository accountRepository,
                       TermsVersionRepository termsVersionRepository,
                       ConsentRecordRepository consentRecordRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       EmailVerificationService emailVerificationService,
                       JwtTokenProvider jwtTokenProvider,
                       PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.termsVersionRepository = termsVersionRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.emailVerificationService = emailVerificationService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // signup() is NOT @Transactional: account creation commits first (in transactionTemplate),
    // then email is sent outside that transaction so an email failure cannot roll back the account.
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().toLowerCase();

        // Phase 1: create account + consents in an isolated transaction that commits immediately.
        Account account = transactionTemplate.execute(status -> {
            if (accountRepository.existsByEmailIgnoreCase(email)) {
                throw new EmailAlreadyExistsException("Email already registered: " + email);
            }
            if (!Boolean.TRUE.equals(request.ageConfirmed())) {
                throw new IllegalArgumentException("Age confirmation is required");
            }
            if (!PASSWORD_POLICY.matcher(request.password()).matches()) {
                throw new IllegalArgumentException("Password must be at least 8 characters with letters and numbers");
            }
            List<TermsVersion> requiredTerms = termsVersionRepository.findByIsActiveTrueAndIsRequiredTrue();
            validateConsents(request.consents(), requiredTerms);

            String hash = passwordEncoder.encode(request.password());
            Account newAccount = Account.builder()
                    .email(email)
                    .passwordHash(hash)
                    .role(AccountRole.USER)
                    .status(AccountStatus.ACTIVE)
                    .signupType(SignupType.EMAIL)
                    .emailVerified(false)
                    .build();
            accountRepository.save(newAccount);

            for (ConsentInput ci : request.consents()) {
                termsVersionRepository.findById(ci.termsVersionId()).ifPresent(tv -> {
                    ConsentRecord cr = ConsentRecord.builder()
                            .account(newAccount)
                            .termsVersion(tv)
                            .agreed(ci.agreed())
                            .build();
                    consentRecordRepository.save(cr);
                });
            }
            return newAccount;
        });

        // Phase 2: send verification email OUTSIDE the account-creation transaction.
        // Email failure no longer rolls back the account. The 503/quota-guard logic in
        // requestCode() (orphan-code prevention, hourly-count) is preserved as-is.
        boolean verificationEmailSent = false;
        try {
            emailVerificationService.requestCode(account);
            verificationEmailSent = true;
        } catch (EmailDeliveryException e) {
            log.warn("Verification email delivery failed for account={}, user can resend", account.getId());
        }

        // Phase 3: 이메일 인증 전이므로 정식 JWT 대신 pendingToken 발급
        String pendingToken = jwtTokenProvider.generateEmailPendingToken(account.getId());
        return new SignupResponse(pendingToken, verificationEmailSent);
    }

    @Transactional
    public Map<String, Object> completeEmailVerification(java.util.UUID accountId, String code) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Account not found"));
        emailVerificationService.verifyCode(account, code);
        // verifyCode() 내부에서 account.verifyEmail() 호출 → emailVerified=true 반영
        TokenPairResponse tokens = tokenService.issueTokenPair(account);
        AccountSummaryResponse accountSummary = buildAccountSummary(account);
        return Map.of("tokens", tokens, "account", accountSummary);
    }

    @Transactional
    public Map<String, Object> login(LoginRequest request) {
        String email = request.email().toLowerCase();

        Account account = accountRepository.findByEmailIgnoreCase(email)
                .orElse(null);

        // Uniform 401 for: not found, locked, wrong password
        if (account == null) {
            throw new BadCredentialsException("Authentication failed");
        }

        if (account.isSuspended()) {
            throw new AccountSuspendedException("Account is suspended");
        }

        // Social-only account (no password): explicit 422 exception (FR-026 exception)
        if (account.getPasswordHash() == null) {
            throw new SocialOnlyAccountException("This account uses social login only");
        }

        // Lockout check — uniform 401 (don't distinguish from wrong password)
        if (account.isLocked()) {
            log.info("Login attempt on locked account={}", account.getId());
            throw new BadCredentialsException("Authentication failed");
        }

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            Instant lockUntil = Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES);
            account.recordLoginFailure(lockUntil);
            AuditLogger.loginFailed(account.getId());
            if (account.isLocked()) {
                log.info("Account locked after {} failed attempts, account={}", MAX_FAILED, account.getId());
                AuditLogger.accountLocked(account.getId());
            }
            accountRepository.save(account);
            throw new BadCredentialsException("Authentication failed");
        }

        // Successful login
        account.resetLoginFailure();
        accountRepository.save(account);

        TokenPairResponse tokens = tokenService.issueTokenPair(account);
        AccountSummaryResponse accountSummary = buildAccountSummary(account);
        return Map.of("tokens", tokens, "account", accountSummary);
    }

    @Transactional
    public TokenPairResponse refresh(String rawRefreshToken) {
        return tokenService.rotate(rawRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokenService.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse buildAccountSummary(Account account) {
        List<TermsVersion> requiredTerms = termsVersionRepository.findByIsActiveTrueAndIsRequiredTrue();
        Set<UUID> consentedVersionIds = consentRecordRepository.findByAccountId(account.getId())
                .stream()
                .filter(ConsentRecord::isAgreed)
                .map(cr -> cr.getTermsVersion().getId())
                .collect(Collectors.toSet());

        boolean requiresReConsent = requiredTerms.stream()
                .anyMatch(tv -> !consentedVersionIds.contains(tv.getId()));

        return new AccountSummaryResponse(
                account.getId(),
                account.getEmail(),
                account.getRole().name(),
                account.isEmailVerified(),
                account.isOnboardingCompleted(),
                account.getSignupType().name(),
                account.getCreatedAt(),
                requiresReConsent
        );
    }

    private void validateConsents(List<ConsentInput> consents, List<TermsVersion> requiredTerms) {
        if (consents == null || consents.isEmpty()) {
            if (!requiredTerms.isEmpty()) {
                throw new IllegalArgumentException("Required terms must be accepted");
            }
            return;
        }
        Map<UUID, Boolean> consentMap = consents.stream()
                .collect(Collectors.toMap(ConsentInput::termsVersionId, ConsentInput::agreed));

        for (TermsVersion tv : requiredTerms) {
            Boolean agreed = consentMap.get(tv.getId());
            if (agreed == null || !agreed) {
                throw new IllegalArgumentException("Required terms must be accepted: " + tv.getType());
            }
        }
    }

}
