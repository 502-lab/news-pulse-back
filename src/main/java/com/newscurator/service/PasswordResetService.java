package com.newscurator.service;

import com.newscurator.client.email.EmailServiceClient;
import com.newscurator.domain.Account;
import com.newscurator.domain.VerificationCode;
import com.newscurator.domain.enums.VerificationPurpose;
import com.newscurator.dto.response.PasswordResetVerifyResponse;
import com.newscurator.exception.EmailDeliveryException;
import com.newscurator.exception.TooManyRequestsException;
import com.newscurator.exception.VerificationCodeExpiredException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.VerificationCodeRepository;
import com.newscurator.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");
    private static final int MAX_HOURLY = 5;
    private static final long CODE_TTL_MINUTES = 15L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final EmailServiceClient emailServiceClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public PasswordResetService(AccountRepository accountRepository,
                                VerificationCodeRepository verificationCodeRepository,
                                EmailServiceClient emailServiceClient,
                                JwtTokenProvider jwtTokenProvider,
                                PasswordEncoder passwordEncoder,
                                TokenService tokenService) {
        this.accountRepository = accountRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.emailServiceClient = emailServiceClient;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /**
     * 비밀번호 재설정 코드 요청. 미등록/소셜 전용/정상 계정 전부 202 반환 (열거 방지).
     * 소셜 전용 계정은 안내 이메일(실패 시 무시), 미등록 계정은 무동작.
     * 정상 계정의 이메일 발송 실패만 EmailDeliveryException을 전파 → 503.
     */
    @Transactional
    public void requestCode(String email) {
        String normalizedEmail = email.toLowerCase();
        Account account = accountRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        if (account == null) {
            return;
        }

        if (account.getPasswordHash() == null) {
            // Social-only: send informational notice, swallow delivery failure
            try {
                emailServiceClient.sendSocialOnlyNotice(normalizedEmail);
            } catch (EmailDeliveryException e) {
                log.warn("Social-only notice email failed for account={}", account.getId());
            }
            return;
        }

        // Normal account: respect hourly limit
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        int recentCount = verificationCodeRepository.maxHourlyCountSince(
                account.getId(), VerificationPurpose.PASSWORD_RESET, windowStart);

        if (recentCount >= MAX_HOURLY) {
            throw new TooManyRequestsException("Too many password reset requests. Try again later.");
        }

        verificationCodeRepository.invalidateAllActive(account.getId(), VerificationPurpose.PASSWORD_RESET);

        String plainCode = String.valueOf(100000 + RANDOM.nextInt(900000));
        String codeHash = jwtTokenProvider.sha256Hex(plainCode);
        int newHourlyCount = recentCount + 1;
        Instant now = Instant.now();

        // Send BEFORE saving — quota charged only on success
        emailServiceClient.sendPasswordResetCode(normalizedEmail, plainCode);

        VerificationCode code = VerificationCode.builder()
                .account(account)
                .purpose(VerificationPurpose.PASSWORD_RESET)
                .codeHash(codeHash)
                .expiresAt(now.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES))
                .hourlyCount(newHourlyCount)
                .windowStart(now)
                .build();
        verificationCodeRepository.save(code);
    }

    /**
     * 6자리 코드 검증 후 단일 사용 resetToken JWT 발급 (10분 TTL).
     * jti = VerificationCode ID (confirmReset에서 단일 사용 추적에 사용).
     * 보안: 계정 미존재와 코드 불일치를 동일한 401로 처리 (열거 방지).
     */
    @Transactional
    public PasswordResetVerifyResponse verifyCode(String email, String code) {
        Account account = accountRepository.findByEmailIgnoreCase(email.toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or code"));

        VerificationCode vc = verificationCodeRepository
                .findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                        account.getId(), VerificationPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or code"));

        if (vc.isExpired()) {
            vc.markUsed();
            throw new VerificationCodeExpiredException("Code has expired");
        }

        String inputHash = jwtTokenProvider.sha256Hex(code);
        if (!inputHash.equals(vc.getCodeHash())) {
            vc.incrementAttempt();
            if (vc.isUsed()) {
                throw new BadCredentialsException("Too many incorrect attempts; code invalidated");
            }
            throw new BadCredentialsException("Invalid email or code");
        }

        // Do NOT mark is_used here — confirmReset will mark it (single-use tracking).
        String resetToken = jwtTokenProvider.generateResetToken(account.getId(), vc.getId());
        return new PasswordResetVerifyResponse(resetToken);
    }

    /**
     * resetToken 검증 후 비밀번호 변경. 성공 시 모든 리프레시 토큰 무효화 (FR-025).
     * is_used=TRUE로 코드 레코드 표시 → resetToken 재사용 시 401.
     */
    @Transactional
    public void confirmReset(String resetToken, String newPassword) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseResetToken(resetToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid or expired reset token");
        }

        UUID accountId = UUID.fromString(claims.getSubject());
        UUID codeId = UUID.fromString(claims.getId());

        VerificationCode vc = verificationCodeRepository.findById(codeId)
                .orElseThrow(() -> new BadCredentialsException("Invalid reset token"));

        if (vc.isUsed()) {
            throw new BadCredentialsException("Reset token has already been used");
        }

        if (!vc.getAccount().getId().equals(accountId)) {
            throw new BadCredentialsException("Invalid reset token");
        }

        if (!PASSWORD_POLICY.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters with letters and numbers");
        }

        vc.markUsed();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadCredentialsException("Account not found"));
        account.updatePassword(passwordEncoder.encode(newPassword));
        accountRepository.save(account);

        tokenService.revokeAllByAccount(account);
    }
}
