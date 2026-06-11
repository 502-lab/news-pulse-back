package com.newscurator.service;

import com.newscurator.config.JwtConfig;
import com.newscurator.domain.Account;
import com.newscurator.domain.RefreshToken;
import com.newscurator.dto.response.TokenPairResponse;
import com.newscurator.exception.TokenReusedException;
import com.newscurator.repository.RefreshTokenRepository;
import com.newscurator.security.JwtTokenProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final long GRACE_PERIOD_SECONDS = 30L;
    private static final long BLAST_WINDOW_SECONDS = 300L; // 5 minutes
    private static final long BLAST_ESCALATION_THRESHOLD = 2L;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtConfig jwtConfig;

    public TokenService(JwtTokenProvider jwtTokenProvider,
                        RefreshTokenRepository refreshTokenRepository,
                        JwtConfig jwtConfig) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtConfig = jwtConfig;
    }

    @Transactional
    public TokenPairResponse issueTokenPair(Account account) {
        return issueTokenPair(account, UUID.randomUUID(), null);
    }

    @Transactional
    public TokenPairResponse issueTokenPair(Account account, UUID familyId, String deviceId) {
        String rawRefresh = jwtTokenProvider.generateRawRefreshToken();
        String tokenHash = jwtTokenProvider.sha256Hex(rawRefresh);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtConfig.getRefreshTtlDays(), ChronoUnit.DAYS);

        RefreshToken refreshToken = RefreshToken.builder()
                .account(account)
                .familyId(familyId)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtTokenProvider.generateAccessToken(account);
        return new TokenPairResponse(accessToken, rawRefresh, jwtConfig.getAccessTtlSeconds());
    }

    @Transactional
    public TokenPairResponse rotate(String rawRefreshToken) {
        String tokenHash = jwtTokenProvider.sha256Hex(rawRefreshToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        // (f) Expired or already-revoked token: reject without blast
        if (existing.isRevoked()) {
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        if (existing.isExpired()) {
            throw new BadCredentialsException("Refresh token has expired");
        }

        if (existing.getConsumedAt() != null) {
            // Token was already consumed — check grace period
            Instant graceDeadline = existing.getConsumedAt().plusSeconds(GRACE_PERIOD_SECONDS);
            if (Instant.now().isBefore(graceDeadline)) {
                // (b) Within grace period — idempotent: issue new token in same family
                RefreshToken latestActive = refreshTokenRepository
                        .findByFamilyIdAndConsumedAtIsNullAndIsRevokedFalse(existing.getFamilyId())
                        .orElseThrow(() -> new BadCredentialsException("No active token in family"));
                // Consume the latest active token and issue a replacement
                latestActive.consume();
                return issueTokenPair(existing.getAccount(), existing.getFamilyId(), existing.getDeviceId());
            } else {
                // (c) Grace period exceeded — family blast.
                // markBlasted() stamps the blast time on the reused token BEFORE the bulk revoke
                // so that countRecentFamilyBlastsByAccountId can use blastedAt as the authoritative
                // blast-time signal rather than consumedAt (which is the rotation time, not blast time).
                log.warn("Token reuse detected beyond grace, blasting family={} account={}",
                        existing.getFamilyId(), existing.getAccount().getId());
                AuditLogger.tokenReuseDetected(existing.getAccount().getId());
                existing.markBlasted();
                refreshTokenRepository.save(existing);
                refreshTokenRepository.revokeByFamilyId(existing.getFamilyId());

                // (d) Check for escalation: 2+ family blasts in last 5 minutes → account-wide
                Instant since = Instant.now().minusSeconds(BLAST_WINDOW_SECONDS);
                long recentBlasts = refreshTokenRepository
                        .countRecentFamilyBlastsByAccountId(existing.getAccount().getId(), since);
                if (recentBlasts >= BLAST_ESCALATION_THRESHOLD) {
                    log.warn("Escalating to account-wide blast for accountId={}",
                            existing.getAccount().getId());
                    refreshTokenRepository.revokeByAccountId(existing.getAccount().getId());
                    throw new TokenReusedException("Account sessions revoked due to suspected token theft");
                }
                throw new TokenReusedException("Token family revoked due to token reuse");
            }
        }

        // (a) Normal rotation: consume this token and issue new one in same family
        existing.consume();
        return issueTokenPair(existing.getAccount(), existing.getFamilyId(), existing.getDeviceId());
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        String tokenHash = jwtTokenProvider.sha256Hex(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(t -> refreshTokenRepository.revokeByFamilyId(t.getFamilyId()));
    }

    @Transactional
    public void revokeAllByAccount(Account account) {
        refreshTokenRepository.revokeByAccountId(account.getId());
    }
}
