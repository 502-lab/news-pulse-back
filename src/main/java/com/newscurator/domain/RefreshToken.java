package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked = false;

    // Set ONLY on family-blast (token reuse attack). NOT set on normal logout/revoke.
    // Used as the authoritative blast-time signal for escalation window queries.
    @Column(name = "blasted_at")
    private Instant blastedAt;

    @Builder
    public RefreshToken(Account account, UUID familyId, String tokenHash,
                        String deviceId, Instant issuedAt, Instant expiresAt) {
        this.account = account;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.deviceId = deviceId;
        this.issuedAt = issuedAt != null ? issuedAt : Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }

    public void revoke() {
        this.isRevoked = true;
    }

    public void markBlasted() {
        this.blastedAt = Instant.now();
    }
}
