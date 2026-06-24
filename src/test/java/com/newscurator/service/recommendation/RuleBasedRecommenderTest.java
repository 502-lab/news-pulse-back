package com.newscurator.service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newscurator.config.FeedRankingProperties;
import com.newscurator.config.InsightsRecommendationProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.FollowKeyword;
import com.newscurator.domain.UserInterests;
import com.newscurator.domain.enums.Category;
import com.newscurator.dto.response.RecommendationResponse;
import com.newscurator.repository.ArticleEventRepository;
import com.newscurator.repository.FollowKeywordRepository;
import com.newscurator.repository.InsightAggregationRepository;
import com.newscurator.repository.UserInterestsRepository;
import com.newscurator.service.ArticleRelevanceScorer;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 010 T019 — RuleBasedRecommender 단위: 콜드스타트 4분기 + 가중치 config 반영(하드코딩 아님). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleBasedRecommenderTest {

    private static final FeedRankingProperties RANKING = new FeedRankingProperties(50, 30, 30, 20, 7);
    private final ArticleRelevanceScorer scorer = new ArticleRelevanceScorer(RANKING);

    @Mock private InsightAggregationRepository aggRepo;
    @Mock private ArticleEventRepository eventRepo;
    @Mock private UserInterestsRepository uiRepo;
    @Mock private FollowKeywordRepository fkRepo;

    private final UUID acc = UUID.randomUUID();

    private RuleBasedRecommender recommender(InsightsRecommendationProperties props) {
        return new RuleBasedRecommender(aggRepo, eventRepo, uiRepo, fkRepo, scorer, props);
    }

    private InsightsRecommendationProperties props(double cat, double trend, double rec) {
        return new InsightsRecommendationProperties(cat, trend, rec, 14, 5, 10);
    }

    private Article article(long id, Category category, String title) {
        Article a =
                Article.builder()
                        .normalizedUrl("u" + id)
                        .originalUrl("u" + id)
                        .title(title)
                        .publishedAt(OffsetDateTime.now())
                        .firstCollectedAt(OffsetDateTime.now())
                        .expiresAt(OffsetDateTime.now().plusDays(90))
                        .build();
        if (category != null) {
            a.completeCategory(category);
        }
        setId(a, id);
        return a;
    }

    private void setId(Article a, long id) {
        try {
            Field f = Article.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private UserInterests interest(String category) {
        UserInterests ui = mock(UserInterests.class);
        when(ui.getCategory()).thenReturn(category);
        return ui;
    }

    private FollowKeyword keyword(String kw) {
        FollowKeyword fk = mock(FollowKeyword.class);
        when(fk.getKeyword()).thenReturn(kw);
        return fk;
    }

    private void candidates(List<Article> arts) {
        when(aggRepo.findRecommendationCandidates(eq(acc), anyInt(), anyInt())).thenReturn(arts);
        lenient().when(aggRepo.trendingTerms(anyInt())).thenReturn(List.of());
        lenient().when(aggRepo.keywordsForArticles(any())).thenReturn(List.of());
    }

    // ── 콜드스타트 4분기 ──────────────────────────────────────────────

    @Test
    @DisplayName("조회0 AND 관심사0 → coldStart=true(트렌드 fallback, 빈 목록 아님)")
    void coldStart_bothZero() {
        when(eventRepo.countDistinctArticlesByAccount(acc)).thenReturn(0L);
        when(uiRepo.findByAccountId(acc)).thenReturn(List.of());
        when(fkRepo.findByAccountId(acc)).thenReturn(List.of());
        candidates(List.of(article(1, Category.IT, "기사")));

        RecommendationResponse r = recommender(props(0.5, 0.3, 0.2)).recommend(acc, 10);
        assertThat(r.coldStart()).isTrue();
        assertThat(r.items()).isNotEmpty();
    }

    @Test
    @DisplayName("조회0 + 관심사有 → coldStart=false(관심사 기반)")
    void interestsOnly_notColdStart() {
        UserInterests ui = interest("POLITICS");
        when(eventRepo.countDistinctArticlesByAccount(acc)).thenReturn(0L);
        when(uiRepo.findByAccountId(acc)).thenReturn(List.of(ui));
        when(fkRepo.findByAccountId(acc)).thenReturn(List.of());
        candidates(List.of(article(1, Category.POLITICS, "기사")));

        RecommendationResponse r = recommender(props(0.5, 0.3, 0.2)).recommend(acc, 10);
        assertThat(r.coldStart()).isFalse();
    }

    @Test
    @DisplayName("조회有 + 관심사0 → coldStart=false(조회 프로파일 기반)")
    void historyOnly_notColdStart() {
        when(eventRepo.countDistinctArticlesByAccount(acc)).thenReturn(3L);
        when(uiRepo.findByAccountId(acc)).thenReturn(List.of());
        when(fkRepo.findByAccountId(acc)).thenReturn(List.of());
        when(aggRepo.categoryDistribution(acc)).thenReturn(List.<Object[]>of(new Object[] {"IT", 3L}));
        when(aggRepo.keywordDistribution(eq(acc), anyInt())).thenReturn(List.of());
        candidates(List.of(article(1, Category.IT, "기사")));

        RecommendationResponse r = recommender(props(0.5, 0.3, 0.2)).recommend(acc, 10);
        assertThat(r.coldStart()).isFalse();
    }

    @Test
    @DisplayName("둘 다 有 → coldStart=false(결합)")
    void both_notColdStart() {
        UserInterests ui = interest("POLITICS");
        FollowKeyword fk = keyword("선거");
        when(eventRepo.countDistinctArticlesByAccount(acc)).thenReturn(3L);
        when(uiRepo.findByAccountId(acc)).thenReturn(List.of(ui));
        when(fkRepo.findByAccountId(acc)).thenReturn(List.of(fk));
        when(aggRepo.categoryDistribution(acc)).thenReturn(List.<Object[]>of(new Object[] {"IT", 3L}));
        when(aggRepo.keywordDistribution(eq(acc), anyInt())).thenReturn(List.of());
        candidates(List.of(article(1, Category.IT, "기사")));

        RecommendationResponse r = recommender(props(0.5, 0.3, 0.2)).recommend(acc, 10);
        assertThat(r.coldStart()).isFalse();
    }

    // ── 가중치 config 반영(하드코딩 아님) ──────────────────────────────

    @Test
    @DisplayName("★ 가중치 변경이 순위를 바꾼다 — categoryWeight 우세 vs trendWeight 우세")
    void weights_changeRanking() {
        UserInterests ui = interest("POLITICS");
        when(eventRepo.countDistinctArticlesByAccount(acc)).thenReturn(0L);
        when(uiRepo.findByAccountId(acc)).thenReturn(List.of(ui));
        when(fkRepo.findByAccountId(acc)).thenReturn(List.of());

        Article catMatch = article(1, Category.POLITICS, "정치 기사"); // 카테고리 매칭(+50)
        Article trendMatch = article(2, Category.IT, "AI 기사"); // 트렌드 키워드 매칭
        when(aggRepo.findRecommendationCandidates(eq(acc), anyInt(), anyInt()))
                .thenReturn(List.of(catMatch, trendMatch));
        when(aggRepo.trendingTerms(anyInt())).thenReturn(List.of("AI"));
        when(aggRepo.keywordsForArticles(any()))
                .thenReturn(List.<Object[]>of(new Object[] {2L, "AI"})); // trendMatch만 트렌딩 키워드

        // categoryWeight 우세 → 카테고리 매칭 기사(1) 먼저
        RecommendationResponse cat =
                recommender(props(1.0, 0.0, 0.0)).recommend(acc, 10);
        assertThat(cat.items().get(0).articleId()).isEqualTo(1L);

        // trendWeight 우세 → 트렌드 매칭 기사(2) 먼저 (하드코딩이면 순위 불변일 것)
        RecommendationResponse trend =
                recommender(props(0.0, 1.0, 0.0)).recommend(acc, 10);
        assertThat(trend.items().get(0).articleId()).isEqualTo(2L);
    }
}
