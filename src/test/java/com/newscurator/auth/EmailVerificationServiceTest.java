package com.newscurator.auth;

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
import com.newscurator.exception.EmailDeliveryException;
import com.newscurator.exception.TooManyRequestsException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.VerificationCodeRepository;
import com.newscurator.security.JwtTokenProvider;
import com.newscurator.service.EmailVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    @Mock private VerificationCodeRepository verificationCodeRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private EmailServiceClient emailServiceClient;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                verificationCodeRepository, accountRepository, emailServiceClient, jwtTokenProvider);
    }

    private Account buildAccount() {
        return Account.builder()
                .email("user@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(false)
                .build();
    }

    @Test
    @DisplayName("requestCode: 이메일 발송 실패 → EmailDeliveryException 전파, 코드 미저장, 한도 미차감")
    void requestCode_emailServiceDown_throws503AndNoOrphanCode() {
        Account account = buildAccount();
        when(verificationCodeRepository.maxHourlyCountSince(any(), eq(VerificationPurpose.EMAIL_VERIFY), any()))
                .thenReturn(0);
        when(jwtTokenProvider.sha256Hex(anyString())).thenReturn("hashed");
        doThrow(new EmailDeliveryException("SMTP down"))
                .when(emailServiceClient).sendVerificationCode(anyString(), anyString());

        assertThatThrownBy(() -> service.requestCode(account))
                .isInstanceOf(EmailDeliveryException.class);

        // Code must NOT be saved (quota not charged)
        verify(verificationCodeRepository, never()).save(any(VerificationCode.class));
    }

    @Test
    @DisplayName("requestCode: 시간당 5회 초과 → TooManyRequestsException, 이메일 미발송")
    void requestCode_rateLimitExceeded_throws429() {
        Account account = buildAccount();
        when(verificationCodeRepository.maxHourlyCountSince(any(), eq(VerificationPurpose.EMAIL_VERIFY), any()))
                .thenReturn(5);

        assertThatThrownBy(() -> service.requestCode(account))
                .isInstanceOf(TooManyRequestsException.class);

        verifyNoInteractions(emailServiceClient);
        verify(verificationCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("requestCode: 정상 → 이메일 발송 + 코드 저장")
    void requestCode_normalPath_savesCodeAndSendsEmail() {
        Account account = buildAccount();
        when(verificationCodeRepository.maxHourlyCountSince(any(), eq(VerificationPurpose.EMAIL_VERIFY), any()))
                .thenReturn(0);
        when(jwtTokenProvider.sha256Hex(anyString())).thenReturn("hashed");

        service.requestCode(account);

        verify(emailServiceClient).sendVerificationCode(eq("user@example.com"), anyString());
        verify(verificationCodeRepository).save(any(VerificationCode.class));
    }
}
