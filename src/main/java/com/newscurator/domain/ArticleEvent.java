package com.newscurator.domain;

import com.newscurator.domain.enums.ArticleEventSource;
import com.newscurator.domain.enums.ArticleEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기사 행동 이벤트(009 읽기 추적, append-only). "안 A" 단일 이벤트 테이블.
 *
 * <p><b>P1=조회(VIEW)·서버기록(SERVER)만 생성</b>. {@code metricValue}는 P1에서 항상 null이며,
 * {@code eventType}/{@code source}/{@code metricValue}는 후속 클라이언트 계측 이벤트(체류·완료율·클릭·공유)를
 * 위한 forward-seam이다(P1 코드는 VIEW·SERVER 외 기록 금지).
 *
 * <p>JPA로는 조회 이벤트 적재만 사용하고, 디바운스 조건부 INSERT·읽은수·이력 질의는
 * {@code ArticleEventRepository}의 native 쿼리로 처리한다.
 */
@Entity
@Table(name = "article_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    private UUID accountId;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private ArticleEventType eventType;

    /** 수치 측정값(체류ms·완료율 등). P1=null(forward-seam). */
    @Column(name = "metric_value")
    private Integer metricValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ArticleEventSource source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Builder
    private ArticleEvent(
            UUID accountId,
            Long articleId,
            ArticleEventType eventType,
            Integer metricValue,
            ArticleEventSource source,
            Instant occurredAt) {
        this.accountId = accountId;
        this.articleId = articleId;
        this.eventType = eventType;
        this.metricValue = metricValue;
        this.source = source;
        this.occurredAt = occurredAt;
    }

    /** P1 조회 이벤트 팩토리 — VIEW·SERVER·metricValue=null·occurredAt=now. */
    public static ArticleEvent view(UUID accountId, Long articleId) {
        return ArticleEvent.builder()
                .accountId(accountId)
                .articleId(articleId)
                .eventType(ArticleEventType.VIEW)
                .metricValue(null)
                .source(ArticleEventSource.SERVER)
                .occurredAt(Instant.now())
                .build();
    }
}
