package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "saved_articles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "article_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    @PrePersist
    protected void onCreate() {
        if (savedAt == null) {
            savedAt = Instant.now();
        }
    }

    @Builder
    public SavedArticle(UUID accountId, Long articleId) {
        this.accountId = accountId;
        this.articleId = articleId;
    }
}
