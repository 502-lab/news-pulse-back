package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.email.EmailServiceClient;
import com.newscurator.domain.Account;
import com.newscurator.domain.VerificationCode;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.domain.enums.VerificationPurpose;
import com.newscurator.dto.response.PasswordResetVerifyResponse;
import com.newscurator.exception.EmailDeliveryException;
import com.newscurator.exception.TooManyRequestsException;
import com.newscurator.exception.VerificationCodeExpiredException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.VerificationCodeRepository;
import com.newscurator.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private VerificationCodeRepository verificationCodeRepository;
    @Mock private EmailServiceClient emailServiceClient;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(
                accountRepository, verificationCodeRepository,
                emailServiceClient, jwtTokenProvider, passwordEncoder, tokenService);
    }

    private Account buildEmailAccount() {
        return Account.builder()
                .email("user@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build();
    }

    private Account buildSocialAccount() {
        return Account.builder()
                .email("social@example.com")
                .passwordHash(null)
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.SOCIAL)
                .emailVerified(true)
                .build();
    }

    private VerificationCode mockVerificationCode(UUID id, boolean isUsed, boolean isExpired) {
        VerificationCode vc = mock(VerificationCode.class);
        when(vc.getId()).thenReturn(id);
        when(vc.isExpired()).thenReturn(isExpired);
        when(vc.isUsed()).thenReturn(isUsed);
        when(vc.getCodeHash()).thenReturn("correct-hash");
        return vc;
    }

    // ────── requestCode ──────

    @Test
    @DisplayName("requestCode: 미등록 이메일 → no-op (이메일 미발송, 예외 없음)")
    void requestCode_unknownEmail_noOp() {
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> service.requestCode("unknown@example.com"));

        verifyNoInteractions(emailServiceClient);
        verifyNoInteractions(verificationCodeRepository);
    }

    @Test
    @DisplayName("requestCode: 소셜 전용 계정 → 안내 이메일 발송, 예외 없음")
    void requestCode_socialOnlyAccount_sendsNotice() {
        Account socialAccount = buildSocialAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(socialAccount));

        service.requestCode("social@example.com");

        verify(emailServiceClient).sendSocialOnlyNotice("social@example.com");
        verify(verificationCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("requestCode: 소셜 전용 계정 이메일 발송 실패 → 예외 삼킴")
    void requestCode_socialOnlyAccount_emailFailure_swallowed() {
        Account socialAccount = buildSocialAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(socialAccount));
        doThrow(new EmailDeliveryException("fail"))
                .when(emailServiceClient).sendSocialOnlyNotice(anyString());

        assertThatNoException().isThrownBy(() -> service.requestCode("social@example.com"));
    }

    @Test
    @DisplayName("requestCode: 정상 계정 → 코드 생성 및 이메일 발송")
    void requestCode_normalAccount_sendsResetCode() {
        Account account = buildEmailAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.maxHourlyCountSince(any(), any(), any())).thenReturn(0);
        when(jwtTokenProvider.sha256Hex(anyString())).thenReturn("hashed");

        service.requestCode("user@example.com");

        verify(emailServiceClient).sendPasswordResetCode(eq("user@example.com"), anyString());
        verify(verificationCodeRepository).save(any(VerificationCode.class));
    }

    @Test
    @DisplayName("requestCode: 한도 초과 → TooManyRequestsException, 이메일 미발송")
    void requestCode_rateLimitExceeded_throws429() {
        Account account = buildEmailAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.maxHourlyCountSince(any(), any(), any())).thenReturn(5);

        assertThatThrownBy(() -> service.requestCode("user@example.com"))
                .isInstanceOf(TooManyRequestsException.class);

        verify(emailServiceClient, never()).sendPasswordResetCode(anyString(), anyString());
        verify(verificationCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("requestCode: 이메일 발송 실패 → EmailDeliveryException 전파, orphan 코드 미생성")
    void requestCode_emailDeliveryFails_throwsAndNoOrphan() {
        Account account = buildEmailAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.maxHourlyCountSince(any(), any(), any())).thenReturn(0);
        when(jwtTokenProvider.sha256Hex(anyString())).thenReturn("hashed");
        doThrow(new EmailDeliveryException("fail"))
                .when(emailServiceClient).sendPasswordResetCode(anyString(), anyString());

        assertThatThrownBy(() -> service.requestCode("user@example.com"))
                .isInstanceOf(EmailDeliveryException.class);

        verify(verificationCodeRepository, never()).save(any());
    }

    // ────── verifyCode ──────

    @Test
    @DisplayName("verifyCode: 미등록 이메일 → BadCredentialsException (열거 방지)")
    void verifyCode_unknownEmail_throws401() {
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyCode("unknown@example.com", "123456"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("verifyCode: 활성 코드 없음 → BadCredentialsException")
    void verifyCode_noActiveCode_throws401() {
        Account account = buildEmailAccount();
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                any(), eq(VerificationPurpose.PASSWORD_RESET))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyCode("user@example.com", "123456"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("verifyCode: 만료 코드 → VerificationCodeExpiredException (410)")
    void verifyCode_expiredCode_throws410() {
        Account account = buildEmailAccount();
        VerificationCode vc = mockVerificationCode(UUID.randomUUID(), false, true);

        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(vc));

        assertThatThrownBy(() -> service.verifyCode("user@example.com", "123456"))
                .isInstanceOf(VerificationCodeExpiredException.class);
    }

    @Test
    @DisplayName("verifyCode: 코드 불일치 → BadCredentialsException, attempt 증가")
    void verifyCode_wrongCode_incrementsAttempt() {
        Account account = buildEmailAccount();
        VerificationCode vc = mockVerificationCode(UUID.randomUUID(), false, false);

        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(vc));
        when(jwtTokenProvider.sha256Hex("wrong")).thenReturn("wrong-hash");

        assertThatThrownBy(() -> service.verifyCode("user@example.com", "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        verify(vc).incrementAttempt();
    }

    @Test
    @DisplayName("verifyCode: 정상 코드 → resetToken 발급, is_used 미변경 (confirmReset이 담당)")
    void verifyCode_validCode_returnsResetToken() {
        Account account = buildEmailAccount();
        UUID codeId = UUID.randomUUID();
        VerificationCode vc = mockVerificationCode(codeId, false, false);

        when(accountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(account));
        when(verificationCodeRepository.findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                any(), any())).thenReturn(Optional.of(vc));
        when(jwtTokenProvider.sha256Hex("123456")).thenReturn("correct-hash");
        when(jwtTokenProvider.generateResetToken(any(), eq(codeId))).thenReturn("reset.jwt.token");

        PasswordResetVerifyResponse resp = service.verifyCode("user@example.com", "123456");

        assertThat(resp.resetToken()).isEqualTo("reset.jwt.token");
        verify(vc, never()).markUsed();
    }

    // ────── confirmReset ──────

    @Test
    @DisplayName("confirmReset: 이미 사용된 토큰 → BadCredentialsException")
    void confirmReset_alreadyUsed_throws401() {
        UUID accountId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        Account accountMock = mock(Account.class);
        when(accountMock.getId()).thenReturn(accountId);
        VerificationCode vc = mockVerificationCode(codeId, true, false);
        when(vc.getAccount()).thenReturn(accountMock);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(accountId.toString());
        when(claims.getId()).thenReturn(codeId.toString());
        when(jwtTokenProvider.parseResetToken("used.token")).thenReturn(claims);
        when(verificationCodeRepository.findById(codeId)).thenReturn(Optional.of(vc));

        assertThatThrownBy(() -> service.confirmReset("used.token", "NewPass123"))
                .isInstanceOf(BadCredentialsException.class);

        verify(tokenService, never()).revokeAllByAccount(any());
    }

    @Test
    @DisplayName("confirmReset: 비밀번호 정책 위반 → IllegalArgumentException")
    void confirmReset_weakPassword_throwsValidationError() {
        UUID accountId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        Account accountMock = mock(Account.class);
        when(accountMock.getId()).thenReturn(accountId);
        VerificationCode vc = mockVerificationCode(codeId, false, false);
        when(vc.getAccount()).thenReturn(accountMock);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(accountId.toString());
        when(claims.getId()).thenReturn(codeId.toString());
        when(jwtTokenProvider.parseResetToken("valid.token")).thenReturn(claims);
        when(verificationCodeRepository.findById(codeId)).thenReturn(Optional.of(vc));

        assertThatThrownBy(() -> service.confirmReset("valid.token", "weak"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(tokenService, never()).revokeAllByAccount(any());
    }

    @Test
    @DisplayName("confirmReset: 유효한 토큰 + 강한 비밀번호 → 비밀번호 변경 + 토큰 전체 무효화 (FR-025)")
    void confirmReset_validToken_changesPasswordAndRevokesAllTokens() {
        UUID accountId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        Account accountMock = mock(Account.class);
        when(accountMock.getId()).thenReturn(accountId);
        VerificationCode vc = mockVerificationCode(codeId, false, false);
        when(vc.getAccount()).thenReturn(accountMock);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(accountId.toString());
        when(claims.getId()).thenReturn(codeId.toString());
        when(jwtTokenProvider.parseResetToken("valid.token")).thenReturn(claims);
        when(verificationCodeRepository.findById(codeId)).thenReturn(Optional.of(vc));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountMock));
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        service.confirmReset("valid.token", "NewPass123");

        verify(vc).markUsed();
        verify(accountMock).updatePassword("new-hash");
        verify(accountRepository).save(accountMock);
        verify(tokenService).revokeAllByAccount(accountMock);
    }
}
