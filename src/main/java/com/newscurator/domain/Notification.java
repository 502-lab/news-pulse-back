package com.newscurator.domain;

import com.newscurator.domain.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // 008 FR-042: 어드민 인앱 알림 멱등 키(결정적). 일반 알림은 null. 부분 unique 인덱스로 유일성.
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Builder
    public Notification(UUID accountId, NotificationType type, String title, String body, String referenceId) {
        this.accountId = accountId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.isRead = false;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(90, ChronoUnit.DAYS);
    }

    public void markRead() {
        this.isRead = true;
    }
}
