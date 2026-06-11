package com.newscurator.service;

import com.newscurator.client.email.EmailServiceClient;
import com.newscurator.domain.Account;
import com.newscurator.domain.VerificationCode;
import com.newscurator.domain.enums.VerificationPurpose;
import com.newscurator.exception.TooManyRequestsException;
import com.newscurator.exception.VerificationCodeExpiredException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.VerificationCodeRepository;
import com.newscurator.security.JwtTokenProvider;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int CODE_LENGTH = 6;
    private static final int MAX_HOURLY = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final long CODE_TTL_MINUTES = 15L;

    private final VerificationCodeRepository verificationCodeRepository;
    private final AccountRepository accountRepository;
    private final EmailServiceClient emailServiceClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    private String generateCode() {
        int code = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(code);
    }

    public EmailVerificationService(VerificationCodeRepository verificationCodeRepository,
                                    AccountRepository accountRepository,
                                    EmailServiceClient emailServiceClient,
                                    JwtTokenProvider jwtTokenProvider) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.accountRepository = accountRepository;
        this.emailServiceClient = emailServiceClient;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public void requestCode(Account account) {
        // Check hourly limit
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        int recentCount = verificationCodeRepository.maxHourlyCountSince(
                account.getId(), VerificationPurpose.EMAIL_VERIFY, windowStart);

        if (recentCount >= MAX_HOURLY) {
            throw new TooManyRequestsException("Too many verification code requests. Try again later.");
        }

        // Invalidate existing unused codes
        verificationCodeRepository.invalidateAllActive(account.getId(), VerificationPurpose.EMAIL_VERIFY);

        String plainCode = generateCode();
        String codeHash = jwtTokenProvider.sha256Hex(plainCode);

        // Compute new hourly count
        int newHourlyCount = recentCount + 1;
        Instant now = Instant.now();

        // Attempt to send BEFORE saving (quota charged only on success)
        emailServiceClient.sendVerificationCode(account.getEmail(), plainCode);

        VerificationCode code = VerificationCode.builder()
                .account(account)
                .purpose(VerificationPurpose.EMAIL_VERIFY)
                .codeHash(codeHash)
                .expiresAt(now.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES))
                .hourlyCount(newHourlyCount)
                .windowStart(now)
                .build();
        verificationCodeRepository.save(code);
    }

    @Transactional
    public void verifyCode(Account account, String plainCode) {
        VerificationCode vc = verificationCodeRepository
                .findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                        account.getId(), VerificationPurpose.EMAIL_VERIFY)
                .orElseThrow(() -> new BadCredentialsException("No active verification code found"));

        if (vc.isExpired()) {
            vc.markUsed();
            throw new VerificationCodeExpiredException("Verification code has expired");
        }

        String inputHash = jwtTokenProvider.sha256Hex(plainCode);
        if (!inputHash.equals(vc.getCodeHash())) {
            vc.incrementAttempt(); // marks used if >= 5 attempts
            if (vc.isUsed()) {
                throw new BadCredentialsException("Too many incorrect attempts; code invalidated");
            }
            throw new BadCredentialsException("Invalid verification code");
        }

        vc.markUsed();
        account.verifyEmail();
        accountRepository.save(account);
    }
}
