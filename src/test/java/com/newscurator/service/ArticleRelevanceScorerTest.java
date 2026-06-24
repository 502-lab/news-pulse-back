package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.newscurator.config.FeedRankingProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.enums.Category;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 010 T010(크라운주얼 #1 일부) — ArticleRelevanceScorer가 구 FeedService.computeScore와 동일 점수 산출.
 * RANKING_PROPS는 FeedServiceTest와 동일(50,30,30,20,7)로 두어 동일성 비교.
 */
class ArticleRelevanceScorerTest {

    private static final FeedRankingProperties PROPS = new FeedRankingProperties(50, 30, 30, 20, 7);
    private final ArticleRelevanceScorer scorer = new ArticleRelevanceScorer(PROPS);

    private Article article(String title, Category category, OffsetDateTime publishedAt) {
        Article a =
                Article.builder()
                        .normalizedUrl("u")
                        .originalUrl("u")
                        .title(title)
                        .publishedAt(publishedAt)
                        .firstCollectedAt(publishedAt)
                        .expiresAt(publishedAt.plusDays(90))
                        .build();
        if (category != null) {
            a.completeCategory(category);
        }
        return a;
    }

    @Test
    @DisplayName("카테고리 매칭(+50) + 방금 발행(최근성 +20) = 70")
    void categoryMatch_plus_recency() {
        Instant ref = Instant.parse("2026-06-24T00:00:00Z");
        Article a = article("뉴스", Category.POLITICS, ref.atOffset(ZoneOffset.UTC));
        double score = scorer.score(a, List.of("POLITICS"), List.of(), ref);
        assertThat(score).isCloseTo(70.0, within(0.001)); // 50 + 20*(1-0/30)
    }

    @Test
    @DisplayName("카테고리 불일치 + 오래된 기사(>30h) = 0")
    void noMatch_oldArticle_zero() {
        Instant ref = Instant.parse("2026-06-24T00:00:00Z");
        Article a = article("뉴스", Category.POLITICS, ref.minusSeconds(40 * 3600).atOffset(ZoneOffset.UTC));
        double score = scorer.score(a, List.of("TECH"), List.of(), ref);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("키워드 매칭 1건(+30) — 제목 포함 시")
    void keywordMatch() {
        Instant ref = Instant.parse("2026-06-24T00:00:00Z");
        // 발행 31h 전 → 최근성 0(윈도우 30h 밖), 카테고리 없음 → 키워드 점수만
        Article a = article("삼성전자 실적", null, ref.minusSeconds(31 * 3600).atOffset(ZoneOffset.UTC));
        double score = scorer.score(a, List.of(), List.of("삼성전자"), ref);
        assertThat(score).isEqualTo(30.0);
    }
}
