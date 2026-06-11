package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.config.JwtConfig;
import com.newscurator.domain.Account;
import com.newscurator.domain.RefreshToken;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.response.TokenPairResponse;
import com.newscurator.exception.TokenReusedException;
import com.newscurator.repository.RefreshTokenRepository;
import com.newscurator.security.JwtTokenProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    private JwtConfig jwtConfig;
    private TokenService tokenService;

    private Account account;
    private UUID familyId;

    @BeforeEach
    void setUp() {
        jwtConfig = new JwtConfig();
        jwtConfig.setSecret("test-secret-minimum-32-chars-for-hmac!!");
        jwtConfig.setAccessTtlSeconds(3600L);
        jwtConfig.setRefreshTtlDays(30);

        tokenService = new TokenService(jwtTokenProvider, refreshTokenRepository, jwtConfig);

        account = Account.builder()
                .email("test@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(false)
                .build();
        familyId = UUID.randomUUID();

        // sha256Hex is used by all tests (in rotate())
        when(jwtTokenProvider.sha256Hex(anyString())).thenAnswer(inv -> "hash_" + inv.getArgument(0));
        // these are only needed for tests that reach issueTokenPair — mark lenient
        lenient().when(jwtTokenProvider.generateRawRefreshToken()).thenReturn("rawtoken");
        lenient().when(jwtTokenProvider.generateAccessToken(any())).thenReturn("accesstoken");
        lenient().when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RefreshToken buildToken(boolean consumed, boolean revoked, boolean expired,
                                    Instant consumedAt) {
        Instant now = Instant.now();
        Instant expiresAt = expired ? now.minus(1, ChronoUnit.HOURS) : now.plus(30, ChronoUnit.DAYS);
        RefreshToken token = RefreshToken.builder()
                .account(account)
                .familyId(familyId)
                .tokenHash("hash_rawtoken")
                .deviceId(null)
                .issuedAt(now.minus(1, ChronoUnit.HOURS))
                .expiresAt(expiresAt)
                .build();
        if (consumed) {
            // Reflectively set consumedAt since consume() sets to Instant.now()
            try {
                var field = RefreshToken.class.getDeclaredField("consumedAt");
                field.setAccessible(true);
                field.set(token, consumedAt != null ? consumedAt : now.minus(1, ChronoUnit.HOURS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (revoked) {
            try {
                var field = RefreshToken.class.getDeclaredField("isRevoked");
                field.setAccessible(true);
                field.set(token, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return token;
    }

    @Test
    @DisplayName("(a) 정상 rotation: consumedAt 설정 + 동일 familyId + 새 토큰 발급")
    void rotate_normalRotation_consumesAndIssuesNew() {
        RefreshToken existing = buildToken(false, false, false, null);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(existing));

        TokenPairResponse result = tokenService.rotate("rawtoken");

        assertThat(existing.getConsumedAt()).isNotNull();
        assertThat(result.accessToken()).isEqualTo("accesstoken");
        assertThat(result.refreshToken()).isNotNull();
        // revokeByFamilyId must NOT be called
        verify(refreshTokenRepository, never()).revokeByFamilyId(any());
        // revokeByAccountId must NOT be called
        verify(refreshTokenRepository, never()).revokeByAccountId(any());
    }

    @Test
    @DisplayName("(b) grace 30s 내 재사용: family 최신 active 토큰 소비, revokeByFamilyId 미호출")
    void rotate_withinGrace_idempotentReuse() {
        // consumed 25 seconds ago → still within grace window
        Instant consumedAt = Instant.now().minusSeconds(25);
        RefreshToken consumed = buildToken(true, false, false, consumedAt);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(consumed));

        RefreshToken activeInFamily = buildToken(false, false, false, null);
        when(refreshTokenRepository.findByFamilyIdAndConsumedAtIsNullAndIsRevokedFalse(familyId))
                .thenReturn(Optional.of(activeInFamily));

        TokenPairResponse result = tokenService.rotate("rawtoken");

        // active token in family was consumed
        assertThat(activeInFamily.getConsumedAt()).isNotNull();
        // New token issued
        assertThat(result.accessToken()).isEqualTo("accesstoken");
        // revokeByFamilyId must NOT be called
        verify(refreshTokenRepository, never()).revokeByFamilyId(any());
    }

    @Test
    @DisplayName("(c) grace 초과 재사용: family blast 발생, 다른 family 미영향")
    void rotate_graceExceeded_familyBlastOnlyThisFamily() {
        // consumed 60 seconds ago → grace (30s) exceeded
        Instant consumedAt = Instant.now().minusSeconds(60);
        RefreshToken consumed = buildToken(true, false, false, consumedAt);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(consumed));
        // Not enough blasts for account-wide escalation (count = 0)
        when(refreshTokenRepository.countRecentFamilyBlastsByAccountId(any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> tokenService.rotate("rawtoken"))
                .isInstanceOf(TokenReusedException.class)
                .hasMessageContaining("family");

        // revokeByFamilyId called for THIS family
        verify(refreshTokenRepository).revokeByFamilyId(familyId);
        // revokeByAccountId must NOT be called
        verify(refreshTokenRepository, never()).revokeByAccountId(any());
    }

    @Test
    @DisplayName("(d) 5분 내 2회 family blast → account-wide 에스컬레이션 + TokenReusedException")
    void rotate_twoBlastsInFiveMin_accountWideEscalation() {
        Instant consumedAt = Instant.now().minusSeconds(60);
        RefreshToken consumed = buildToken(true, false, false, consumedAt);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(consumed));
        // countRecentFamilyBlasts returns >= 2 → triggers account-wide
        when(refreshTokenRepository.countRecentFamilyBlastsByAccountId(any(), any())).thenReturn(2L);

        assertThatThrownBy(() -> tokenService.rotate("rawtoken"))
                .isInstanceOf(TokenReusedException.class);

        // Both family and account-wide revocations called
        verify(refreshTokenRepository).revokeByFamilyId(familyId);
        verify(refreshTokenRepository).revokeByAccountId(account.getId());
    }

    @Test
    @DisplayName("(e) 에스컬레이션 window 리셋: 1회 blast → 5분 경과 → 1회 → count=1 → 에스컬레이션 안 됨")
    void rotate_blastWindowReset_noEscalation() {
        Instant consumedAt = Instant.now().minusSeconds(60);
        RefreshToken consumed = buildToken(true, false, false, consumedAt);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(consumed));
        // count = 1 (only 1 blast in window, the second blast is outside the 5-min window)
        when(refreshTokenRepository.countRecentFamilyBlastsByAccountId(any(), any())).thenReturn(1L);

        // Should throw family-blast exception but NOT account-wide
        assertThatThrownBy(() -> tokenService.rotate("rawtoken"))
                .isInstanceOf(TokenReusedException.class)
                .hasMessageContaining("family");

        verify(refreshTokenRepository).revokeByFamilyId(familyId);
        // account-wide must NOT be triggered
        verify(refreshTokenRepository, never()).revokeByAccountId(any());
    }

    @Test
    @DisplayName("(f) 만료/취소된 토큰 재사용: 거부, blast 미발생")
    void rotate_expiredOrRevokedToken_rejectedWithoutBlast() {
        // Test revoked token
        RefreshToken revoked = buildToken(false, true, false, null);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> tokenService.rotate("rawtoken"))
                .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository, never()).revokeByFamilyId(any());
        verify(refreshTokenRepository, never()).revokeByAccountId(any());

        // Test expired token
        RefreshToken expired = buildToken(false, false, true, null);
        when(refreshTokenRepository.findByTokenHash("hash_rawtoken")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.rotate("rawtoken"))
                .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository, never()).revokeByFamilyId(any());
        verify(refreshTokenRepository, never()).revokeByAccountId(any());
    }
}
