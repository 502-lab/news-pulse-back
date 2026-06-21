package com.newscurator.domain;

import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.domain.enums.NotificationOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    private UUID accountId;

    @Column(name = "notification_id")
    private Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationOutboxStatus status = NotificationOutboxStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public NotificationOutbox(UUID accountId, Long notificationId, NotificationChannel channel,
                               String payload, String idempotencyKey) {
        this.accountId = accountId;
        this.notificationId = notificationId;
        this.channel = channel;
        this.status = NotificationOutboxStatus.PENDING;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.attemptCount = 0;
        Instant now = Instant.now();
        this.nextRetryAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markProcessing() {
        this.status = NotificationOutboxStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markSent() {
        this.status = NotificationOutboxStatus.SENT;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = NotificationOutboxStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    /**
     * 재시도 카운트 증가 + 백오프 적용. 3회 도달 시 FAILED로 전환.
     * 백오프: 1회→1분, 2회→5분, 3회→FAILED.
     */
    public void incrementAttemptWithBackoff() {
        this.attemptCount++;
        Instant now = Instant.now();
        if (this.attemptCount >= 3) {
            this.status = NotificationOutboxStatus.FAILED;
        } else {
            this.status = NotificationOutboxStatus.PENDING;
            this.nextRetryAt = switch (this.attemptCount) {
                case 1 -> now.plus(1, ChronoUnit.MINUTES);
                case 2 -> now.plus(5, ChronoUnit.MINUTES);
                default -> now.plus(15, ChronoUnit.MINUTES);
            };
        }
        this.updatedAt = now;
    }
}
