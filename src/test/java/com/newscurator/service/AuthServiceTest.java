package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.LoginRequest;
import com.newscurator.exception.SocialOnlyAccountException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ConsentRecordRepository;
import com.newscurator.repository.TermsVersionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TermsVersionRepository termsVersionRepository;
    @Mock private ConsentRecordRepository consentRecordRepository;
    @Mock private TokenService tokenService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private PlatformTransactionManager transactionManager;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    private static final String VALID_PASSWORD = "Password1";
    private static final String WRONG_PASSWORD = "WrongPassword1";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(10);
        // lenient: only signup() exercises TransactionTemplate; login/refresh tests don't need it.
        TransactionStatus txStatus = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        authService = new AuthService(accountRepository, termsVersionRepository,
                consentRecordRepository, passwordEncoder, tokenService, emailVerificationService,
                transactionManager);
    }

    private Account buildActiveEmailAccount(String email, String rawPassword) {
        return Account.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(false)
                .build();
    }

    @Test
    @DisplayName("연속 5회 로그인 실패 → 계정 잠금(lockedUntil 설정)")
    void login_fiveConsecutiveFailures_locksAccount() {
        Account account = buildActiveEmailAccount("lock@example.com", VALID_PASSWORD);
        when(accountRepository.findByEmailIgnoreCase("lock@example.com")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        LoginRequest req = new LoginRequest("lock@example.com", WRONG_PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(req))
                    .isInstanceOf(BadCredentialsException.class);
        }

        // After 5 failures the account should be locked
        assertThat(account.getFailedLoginCount()).isEqualTo(5);
        assertThat(account.isLocked()).isTrue();
    }

    @Test
    @DisplayName("5회 실패 후 올바른 비밀번호로 로그인 시도 → 여전히 401")
    void login_correctPasswordAfterLock_stillReturns401() {
        Account account = buildActiveEmailAccount("locked@example.com", VALID_PASSWORD);
        when(accountRepository.findByEmailIgnoreCase("locked@example.com")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        LoginRequest wrongReq = new LoginRequest("locked@example.com", WRONG_PASSWORD);
        // Trigger 5 failures to lock
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(wrongReq))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThat(account.isLocked()).isTrue();

        // Now try with the correct password — must still get 401 (locked)
        LoginRequest correctReq = new LoginRequest("locked@example.com", VALID_PASSWORD);
        assertThatThrownBy(() -> authService.login(correctReq))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("30분 후 잠금 자동 해제 → 로그인 성공")
    void login_afterLockExpiry_succeeds() {
        Account account = buildActiveEmailAccount("unlocked@example.com", VALID_PASSWORD);
        // Pre-set lockedUntil to 31 minutes ago (already expired)
        account.recordLoginFailure(Instant.now().minus(31, ChronoUnit.MINUTES));
        when(accountRepository.findByEmailIgnoreCase("unlocked@example.com")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(termsVersionRepository.findByIsActiveTrueAndIsRequiredTrue()).thenReturn(List.of());
        when(consentRecordRepository.findByAccountId(any())).thenReturn(List.of());
        when(tokenService.issueTokenPair(account)).thenReturn(
                new com.newscurator.dto.response.TokenPairResponse("access", "refresh", 3600));

        // Lock should be expired
        assertThat(account.isLocked()).isFalse();

        LoginRequest req = new LoginRequest("unlocked@example.com", VALID_PASSWORD);
        var result = authService.login(req);
        assertThat(result).containsKey("tokens");
    }

    @Test
    @DisplayName("소셜 전용 계정(password_hash=null) 비밀번호 로그인 → 422 SOCIAL_ONLY_ACCOUNT")
    void login_socialOnlyAccount_returns422() {
        // Simulate a SOCIAL account with no password hash (as would be seeded in integration tests)
        Account socialAccount = Account.builder()
                .email("social@example.com")
                .passwordHash(null) // social-only: no password
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.SOCIAL)
                .emailVerified(true)
                .build();
        when(accountRepository.findByEmailIgnoreCase("social@example.com"))
                .thenReturn(Optional.of(socialAccount));

        LoginRequest req = new LoginRequest("social@example.com", "anyPassword1");
        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(SocialOnlyAccountException.class);
    }
}
