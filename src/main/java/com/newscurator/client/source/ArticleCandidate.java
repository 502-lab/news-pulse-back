package com.newscurator.client.source;

import java.time.OffsetDateTime;

/**
 * SourceAdapter가 반환하는 수집 후보 기사 VO.
 * Entity 변환은 CollectionService에서 처리.
 */
public record ArticleCandidate(
        String url,
        String title,
        String author,
        OffsetDateTime publishedAt,
        String description) {}
