package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 010 놓친 기사 추천 튜닝 파라미터(런타임 조정, 하드코딩 금지).
 *
 * <p>블렌드 가중치(category/trend/recency)는 {@code FeedRankingProperties}의 base 점수 위에 적용된다.
 */
@ConfigurationProperties(prefix = "app.insights.recommendation")
public record InsightsRecommendationProperties(
        double categoryWeight,
        double trendWeight,
        double recencyWeight,
        int candidateDays,
        int minSampleSize,
        int recommendLimit) {}
