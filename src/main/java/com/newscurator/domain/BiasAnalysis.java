package com.newscurator.domain;

import com.newscurator.domain.enums.BiasStatus;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 기사별 편향 분석 작업 단위 (1기사 1행).
 *
 * <p>상태 전이는 status로 추적하며 별도 Job/큐 엔티티를 두지 않는다. two-tx claimer + lease 모델
 * (research R-013): {@link #claim(int)}이 next_retry_at을 미래로 미뤄 크래시 stuck 행을 회수한다.
 */
@Entity
@Table(name = "bias_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BiasAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false, unique = true)
    private Long articleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BiasStatus status = BiasStatus.PENDING;

    @Column(name = "value")
    private Integer value;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rationale_keywords", columnDefinition = "text[]")
    private String[] rationaleKeywords;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public BiasAnalysis(Long articleId) {
        this.articleId = articleId;
        this.status = BiasStatus.PENDING;
        this.attemptCount = 0;
        Instant now = Instant.now();
        this.nextRetryAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * claim 시 next_retry_at을 NOW()+lease로 미뤄, 처리 중 크래시(PROCESSING 고아) 시
     * lease 만료 후 claimer가 회수하도록 한다. 정상 완료/실패는
     * complete()/incrementAttemptWithBackoff()가 덮어쓴다.
     */
    public void claim(int leaseMinutes) {
        this.status = BiasStatus.PROCESSING;
        this.nextRetryAt = Instant.now().plus(leaseMinutes, ChronoUnit.MINUTES);
    }

    public void complete(int value, String[] keywords) {
        this.value = value;
        this.rationaleKeywords = keywords;
        this.status = BiasStatus.DONE;
        this.analyzedAt = Instant.now();
    }

    public void incrementAttemptWithBackoff(int attempt1Minutes, int attempt2Minutes) {
        this.attemptCount++;
        Instant now = Instant.now();
        if (this.attemptCount >= 3) {
            this.status = BiasStatus.FAILED;
            this.failedAt = now;
        } else {
            this.status = BiasStatus.PENDING;
            this.nextRetryAt = switch (this.attemptCount) {
                case 1 -> now.plus(attempt1Minutes, ChronoUnit.MINUTES);
                case 2 -> now.plus(attempt2Minutes, ChronoUnit.MINUTES);
                default -> now.plus(attempt2Minutes, ChronoUnit.MINUTES);
            };
        }
    }

    public void completeOneShot(int value, String[] keywords) {
        complete(value, keywords);
    }

    public void failTerminal() {
        this.attemptCount++; // 3 → 4: recovery 인덱스 술어(attempt_count=3) 에서 영구 이탈, 루프 방지
        this.status = BiasStatus.FAILED;
    }
}
