package com.newscurator.domain;

import com.newscurator.domain.enums.VerificationPurpose;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "verification_codes")
@Getter
@NoArgsConstructor
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VerificationPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "hourly_count", nullable = false)
    private int hourlyCount = 0;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "is_used", nullable = false)
    private boolean isUsed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public VerificationCode(Account account, VerificationPurpose purpose,
                            String codeHash, Instant expiresAt,
                            int hourlyCount, Instant windowStart) {
        this.account = account;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.hourlyCount = hourlyCount;
        this.windowStart = windowStart != null ? windowStart : Instant.now();
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        if (this.windowStart == null) {
            this.windowStart = Instant.now();
        }
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public void incrementAttempt() {
        this.attemptCount++;
        if (this.attemptCount >= 5) {
            this.isUsed = true;
        }
    }

    public void markUsed() {
        this.isUsed = true;
    }
}
