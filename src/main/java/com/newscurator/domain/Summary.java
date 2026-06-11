package com.newscurator.domain;

import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
        name = "summaries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"article_id", "depth"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SummaryDepth depth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SummarySlotStatus status = SummarySlotStatus.NOT_GENERATED;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;

    // DEEP 슬롯 전용: 마지막 AI 시도 시각 (BALANCED/BRIEF는 NULL 유지)
    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    // DEEP 슬롯 전용: 누적 AI 시도 횟수 (BALANCED/BRIEF는 0 유지)
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public Summary(Article article, SummaryDepth depth) {
        this.article = article;
        this.depth = depth;
    }

    public void markPending() {
        this.status = SummarySlotStatus.PENDING;
    }

    public void complete(String content) {
        this.content = content;
        this.status = SummarySlotStatus.COMPLETED;
        this.generatedAt = OffsetDateTime.now();
    }

    public void completeWithoutAi(String content) {
        this.content = content;
        this.status = SummarySlotStatus.COMPLETED;
        this.generatedAt = OffsetDateTime.now();
        this.aiGenerated = false;
    }

    public void failDeepSlot() {
        this.status = SummarySlotStatus.FAILED;
        this.lastAttemptAt = OffsetDateTime.now();
        this.retryCount++;
    }
}
