package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "article_sources",
        uniqueConstraints = @UniqueConstraint(columnNames = {"article_id", "source_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    // is_merge=true: 두 번째 이상 수집(병합); false: 최초 수집
    @Column(name = "is_merge", nullable = false)
    private boolean merge = false;

    @Builder
    public ArticleSource(Article article, Source source, OffsetDateTime collectedAt, boolean merge) {
        this.article = article;
        this.source = source;
        this.collectedAt = collectedAt;
        this.merge = merge;
    }
}
