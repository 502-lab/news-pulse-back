package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.newscurator.client.email.EmailServiceClient;
import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.LoginRequest;
import com.newscurator.exception.EmailDeliveryException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ConsentRecordRepository;
import com.newscurator.repository.TermsVersionRepository;
import com.newscurator.repository.VerificationCodeRepository;
import com.newscurator.security.JwtTokenProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FR-028 실제 경로 PII 누출 검증.
 * 루트 로거에 appender를 부착해 AuthService·PasswordResetService의 실제 실행 경로에서
 * 이메일·비밀번호 평문이 어떤 로거를 통해서도 출력되지 않음을 단언한다.
 * AuditLoggerTest의 시그니처-레벨 보장(UUID-only)과 상호 보완적.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PiiAuditLeakTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @Mock AccountRepository accountRepository;
    @Mock TermsVersionRepository termsVersionRepository;
    @Mock ConsentRecordRepository consentRecordRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenService tokenService;
    @Mock EmailVerificationService emailVerificationService;
    @Mock PlatformTransactionManager transactionManager;
    @Mock VerificationCodeRepository verificationCodeRepository;
    @Mock EmailServiceClient emailServiceClient;
    @Mock JwtTokenProvider jwtTokenProvider;

    private AuthService authService;
    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        // ROOT 로거에 부착 — AuthService·PasswordResetService·AuditLogger 등
        // 모든 패키지 로그를 한 번에 캡처.
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        authService = new AuthService(accountRepository, termsVersionRepository,
                consentRecordRepository, passwordEncoder, tokenService,
                emailVerificationService, transactionManager);
        passwordResetService = new PasswordResetService(accountRepository,
                verificationCodeRepository, emailServiceClient, jwtTokenProvider,
                passwordEncoder, tokenService);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(appender);
    }

    // ─── helpers ───

    private Account stubEmailAccount(String email) {
        Account account = Account.builder()
                .email(email)
                .passwordHash("$2a$10$fakehash12345678901234.abcdefghij")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .onboardingCompleted(false)
                .signupType(SignupType.EMAIL)
                .build();
        injectId(account);
        return account;
    }

    private Account stubSocialAccount(String email) {
        Account account = Account.builder()
                .email(email)
                .passwordHash(null)   // social-only
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .onboardingCompleted(false)
                .signupType(SignupType.SOCIAL)
                .build();
        injectId(account);
        return account;
    }

    private void injectId(Account account) {
        try {
            java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(account, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertNoPii(List<ILoggingEvent> logs, String... forbidden) {
        assertThat(logs).isNotEmpty();  // 코드 경로가 실제로 실행돼 로그가 발생했음을 보장
        for (ILoggingEvent event : logs) {
            String msg = event.getFormattedMessage();
            for (String pii : forbidden) {
                assertThat(msg)
                        .as("Logger [%s] 메시지에 '%s' 포함됨", event.getLoggerName(), pii)
                        .doesNotContain(pii);
            }
        }
    }

    // ─── tests ───

    @Test
    @DisplayName("[FR-028] AuthService.login() 5회 실패→계정잠금: 루트 로그 전체에 이메일·비밀번호 미포함")
    void authService_loginFailure_accountLock_noPiiInAnyLog() {
        String realEmail = "victim@example.com";
        String wrongPassword = "WrongPass1!";
        Account account = stubEmailAccount(realEmail);

        // 4회 선실패 → 5번째 login() 호출 시 ACCOUNT_LOCKED + LOGIN_FAILED 양쪽 로그 발생
        Instant lockUntil = Instant.now().plus(30, ChronoUnit.MINUTES);
        for (int i = 0; i < 4; i++) {
            account.recordLoginFailure(lockUntil);
        }

        when(accountRepository.findByEmailIgnoreCase(realEmail)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(wrongPassword, account.getPasswordHash())).thenReturn(false);
        when(accountRepository.save(any())).thenReturn(account);

        assertThatThrownBy(() -> authService.login(new LoginRequest(realEmail, wrongPassword)))
                .isInstanceOf(BadCredentialsException.class);

        // assertNoPii는 내부에서 isNotEmpty를 단언:
        // AuditLogger.loginFailed + AuditLogger.accountLocked + log.info("Account locked...") 세 건이 발생해야 한다
        assertNoPii(appender.list, realEmail, wrongPassword);
    }

    @Test
    @DisplayName("[FR-028] PasswordResetService.requestCode() 소셜 계정 notice 발송 실패: 루트 로그 전체에 이메일 미포함")
    void passwordResetService_requestCode_socialNoticeFailure_noPiiInAnyLog() {
        String realEmail = "victim@example.com";
        Account socialAccount = stubSocialAccount(realEmail);

        when(accountRepository.findByEmailIgnoreCase(realEmail.toLowerCase()))
                .thenReturn(Optional.of(socialAccount));
        // 이메일 값을 scope 안에서 보유한 채 발송 실패 → catch 블록 실행
        // log.warn("Social-only notice email failed for account={}", account.getId()) 발생
        doThrow(new EmailDeliveryException("SMTP error"))
                .when(emailServiceClient).sendSocialOnlyNotice(realEmail.toLowerCase());

        // 소셜 전용: 예외를 삼킴 — 정상 반환
        passwordResetService.requestCode(realEmail);

        // log.warn이 실행됐고(isNotEmpty 보장), 이메일이 그 로그에 없어야 한다
        assertNoPii(appender.list, realEmail);
    }
}
