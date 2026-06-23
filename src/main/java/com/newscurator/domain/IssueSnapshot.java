package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 최신 co-occurrence 이슈 스냅샷 (re-derive, OI-4). 매 집계 전량 교체 — cross-run 안정 ID 없음.
 * id는 run 내 임시 식별일 뿐 외부 참조를 보장하지 않는다.
 */
@Entity
@Table(name = "issue_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "derived_at", nullable = false)
    private Instant derivedAt;

    @Column(name = "clustering_method", nullable = false, length = 32)
    private String clusteringMethod;

    @Column(name = "delta")
    private BigDecimal delta;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "keywords", columnDefinition = "text[]", nullable = false)
    private String[] keywords;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "article_ids", columnDefinition = "bigint[]", nullable = false)
    private Long[] articleIds;

    @Builder
    public IssueSnapshot(String clusteringMethod, BigDecimal delta, String[] keywords, Long[] articleIds) {
        this.derivedAt = Instant.now();
        this.clusteringMethod = clusteringMethod;
        this.delta = delta;
        this.keywords = keywords;
        this.articleIds = articleIds;
    }
}
