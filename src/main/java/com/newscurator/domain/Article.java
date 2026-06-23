package com.newscurator.domain;

import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "articles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_url", nullable = false, unique = true, length = 2048)
    private String normalizedUrl;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false, length = 1024)
    private String title;

    @Column(length = 512)
    private String author;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "first_collected_at", nullable = false, updatable = false)
    private OffsetDateTime firstCollectedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_status", nullable = false, length = 32)
    private ProcessingStatus categoryStatus = ProcessingStatus.PENDING;

    @Column(name = "category_retry_count", nullable = false)
    private int categoryRetryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_status", nullable = false, length = 32)
    private ProcessingStatus summaryStatus = ProcessingStatus.PENDING;

    @Column(name = "summary_retry_count", nullable = false)
    private int summaryRetryCount = 0;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "feed_visible", nullable = false)
    private boolean feedVisible = true;

    // admin_hidden_at: 관리자 숨김 시각(가역). NULL=노출, NOT NULL=숨김. feed_visible(만료 물리삭제)과 독립(008).
    @Column(name = "admin_hidden_at")
    private OffsetDateTime adminHiddenAt;

    // user_saved: 단일 boolean; 다중 사용자 저장 추적은 spec 002 범위
    @Column(name = "user_saved", nullable = false)
    private boolean userSaved = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ArticleSource> sources = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Summary> summaries = new ArrayList<>();

    @Builder
    public Article(
            String normalizedUrl,
            String originalUrl,
            String title,
            String author,
            OffsetDateTime publishedAt,
            OffsetDateTime firstCollectedAt,
            OffsetDateTime expiresAt) {
        this.normalizedUrl = normalizedUrl;
        this.originalUrl = originalUrl;
        this.title = title;
        this.author = author;
        this.publishedAt = publishedAt;
        this.firstCollectedAt = firstCollectedAt;
        this.expiresAt = expiresAt;
    }

    public void completeCategory(Category category) {
        this.category = category;
        this.categoryStatus = ProcessingStatus.COMPLETED;
    }

    public void incrementCategoryRetry() {
        this.categoryRetryCount++;
    }

    public void failCategory() {
        this.categoryStatus = ProcessingStatus.FAILED;
    }

    public void completeSummary() {
        this.summaryStatus = ProcessingStatus.COMPLETED;
    }

    public void incrementSummaryRetry() {
        this.summaryRetryCount++;
    }

    public void failSummary() {
        this.summaryStatus = ProcessingStatus.FAILED;
    }

    /** 008 US3: 관리자 요약 재시도 트리거 — FAILED 요약을 PENDING으로 되돌려 재처리 큐에 올린다. */
    public void resetSummaryForRetry() {
        this.summaryStatus = ProcessingStatus.PENDING;
    }

    public void hide() {
        this.feedVisible = false;
    }

    /** 관리자 숨김(가역, 008). feed_visible과 독립 — 만료 물리삭제와 무관. */
    public void hideByAdmin(OffsetDateTime at) {
        this.adminHiddenAt = at;
    }

    /** 관리자 숨김 해제(unhide). */
    public void unhideByAdmin() {
        this.adminHiddenAt = null;
    }

    public boolean isAdminHidden() {
        return this.adminHiddenAt != null;
    }
}
