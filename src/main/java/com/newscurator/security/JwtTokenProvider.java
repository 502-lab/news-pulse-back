package com.newscurator.security;

import com.newscurator.config.JwtConfig;
import com.newscurator.domain.Account;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long accessTtlMs;

    public JwtTokenProvider(JwtConfig jwtConfig) {
        byte[] keyBytes = jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlMs = jwtConfig.getAccessTtlSeconds() * 1000L;
    }

    public String generateAccessToken(Account account) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(account.getId().toString())
                .claim("role", account.getRole().name())
                .claim("emailVerified", account.isEmailVerified())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTtlMs))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String generateRawRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "") +
               UUID.randomUUID().toString().replace("-", "");
    }

    /** Issues a 10-minute single-use password-reset token. jti = verificationCode ID. */
    public String generateResetToken(UUID accountId, UUID codeId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(accountId.toString())
                .claim("purpose", "PASSWORD_RESET")
                .id(codeId.toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + 10L * 60 * 1000))
                .signWith(signingKey)
                .compact();
    }

    /** Parses and validates a reset token. Throws JwtException on invalid/expired. */
    public Claims parseResetToken(String token) {
        Claims claims = parseToken(token);
        Assert.isTrue("PASSWORD_RESET".equals(claims.get("purpose")), "Token purpose mismatch");
        return claims;
    }

    /**
     * Issues a 10-minute pending-signup token. sub = "{provider}:{providerUserId}".
     * Carries social identity to bridge the callback → complete two-step signup.
     */
    public String generatePendingSignupToken(String provider, String providerUserId, String email) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(provider + ":" + providerUserId)
                .claim("type", "SOCIAL_PENDING")
                .claim("provider", provider)
                .claim("pid", providerUserId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 10L * 60 * 1000))
                .signWith(signingKey);
        if (email != null) builder.claim("email", email);
        return builder.compact();
    }

    /** Parses and validates a pending-signup token. Throws JwtException on invalid/expired. */
    public Claims parsePendingSignupToken(String token) {
        Claims claims = parseToken(token);
        Assert.isTrue("SOCIAL_PENDING".equals(claims.get("type")), "Token type mismatch");
        return claims;
    }

    /** Issues a 5-minute stateless OAuth state token. sub = provider name. */
    public String generateOAuthState(String provider) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(provider)
                .claim("type", "OAUTH_STATE")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 5L * 60 * 1000))
                .signWith(signingKey)
                .compact();
    }

    /** Parses and validates an OAuth state token. Throws JwtException on invalid/expired. */
    public Claims parseOAuthState(String state) {
        Claims claims = parseToken(state);
        Assert.isTrue("OAUTH_STATE".equals(claims.get("type")), "Invalid state token type");
        return claims;
    }

    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
