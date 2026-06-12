package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.config.FeedRankingProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.FollowKeyword;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.Summary;
import com.newscurator.domain.UserInterests;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.KeywordType;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.dto.response.FeedResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.FollowKeywordRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.UserInterestsRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceTest {

    @Mock private UserInterestsRepository userInterestsRepository;
    @Mock private FollowKeywordRepository followKeywordRepository;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private ArticleSourceRepository articleSourceRepository;
    @Mock private SavedArticleRepository savedArticleRepository;

    private FeedService feedService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final FeedRankingProperties RANKING_PROPS =
            new FeedRankingProperties(50, 30, 30, 20, 7);

    @BeforeEach
    void setUp() {
        feedService = new FeedService(
                userInterestsRepository,
                followKeywordRepository,
                readingPreferenceRepository,
                articleRepository,
                summaryRepository,
                articleSourceRepository,
                savedArticleRepository,
                RANKING_PROPS);

        // 기본 stubbing
        when(summaryRepository.findCompletedByArticleIdIn(any())).thenReturn(List.of());
        when(articleSourceRepository.findWithSourceByArticleIdIn(any())).thenReturn(List.of());
        when(savedArticleRepository.findSavedArticleIdsByAccountIdAndArticleIdIn(any(), any()))
                .thenReturn(Set.of());
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.empty());
    }

    // ── T1: 카테고리 점수 우위 ──────────────────────────────────────────────

    @Test
    @DisplayName("카테고리 일치 기사가 비일치 기사보다 높은 순위 (category_score=50)")
    void getFeed_categoryScore_matchedArticleRanksFirst() {
        mockInterests("ECONOMY_FINANCE");
        mockKeywords();

        OffsetDateTime pubAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        Article econArticle = buildArticle(1L, Category.ECONOMY_FINANCE, pubAt);
        Article politicsArticle = buildArticle(2L, Category.POLITICS, pubAt);

        mockCandidates(econArticle, politicsArticle);

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        assertThat(response.personalized()).isTrue();
        assertThat(response.articles()).hasSize(2);
        assertThat(response.articles().get(0).id()).isEqualTo(1L);  // ECONOMY_FINANCE 우선
        assertThat(response.articles().get(1).id()).isEqualTo(2L);
    }

    // ── T2: 키워드 +30점 ───────────────────────────────────────────────────

    @Test
    @DisplayName("팔로우 키워드가 제목에 포함된 기사가 +30점 획득")
    void getFeed_keywordScore_matchingKeywordAdds30Points() {
        mockInterests();
        mockKeywords("경제");

        OffsetDateTime pubAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        Article kwArticle = buildArticleWithTitle(1L, "경제 성장률 예상 상회", Category.ECONOMY_FINANCE, pubAt);
        Article noKwArticle = buildArticleWithTitle(2L, "스포츠 소식", Category.SPORTS, pubAt);

        mockCandidates(kwArticle, noKwArticle);

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        assertThat(response.personalized()).isTrue();
        assertThat(response.articles().get(0).id()).isEqualTo(1L);
        // 키워드 매칭 → rankScore에 30이 반영되어야 함
        assertThat(response.articles().get(0).rankScore()).isGreaterThanOrEqualTo(30.0);
    }

    // ── T3: 최신성 점수 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("방금 발행 기사(0h)는 25시간 전 기사보다 높은 recency_score")
    void getFeed_recencyScore_recentArticleScoresHigher() {
        mockInterests();
        mockKeywords();

        Instant now = Instant.now();
        OffsetDateTime freshPubAt = now.atOffset(ZoneOffset.UTC).minusMinutes(1);
        OffsetDateTime oldPubAt = now.atOffset(ZoneOffset.UTC).minusHours(25);

        Article fresh = buildArticle(1L, Category.IT, freshPubAt);
        Article old = buildArticle(2L, Category.IT, oldPubAt);

        mockCandidates(fresh, old);

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        // 개인화 없이도 recency로 순서 결정
        // articles는 rankScore로 내림차순 정렬되어야 함
        double freshScore = response.articles().stream()
                .filter(a -> a.id().equals(1L)).findFirst().orElseThrow().rankScore();
        double oldScore = response.articles().stream()
                .filter(a -> a.id().equals(2L)).findFirst().orElseThrow().rankScore();

        assertThat(freshScore).isGreaterThan(oldScore);
    }

    // ── T4: 관심사·키워드 없음 → personalized=false, 최신순 ─────────────────

    @Test
    @DisplayName("관심사·키워드 모두 없으면 personalized=false, publishedAt DESC 순서")
    void getFeed_noInterestsOrKeywords_fallbackLatest() {
        mockInterests();   // empty
        mockKeywords();    // empty

        OffsetDateTime newer = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        OffsetDateTime older = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        Article newerArticle = buildArticle(1L, Category.POLITICS, newer);
        Article olderArticle = buildArticle(2L, Category.SPORTS, older);

        mockCandidates(olderArticle, newerArticle);  // 순서 뒤집어도

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        assertThat(response.personalized()).isFalse();
        assertThat(response.articles()).hasSize(2);
        assertThat(response.articles().get(0).id()).isEqualTo(1L);  // newer 먼저
        assertThat(response.articles().get(1).id()).isEqualTo(2L);
    }

    // ── T5: category 파라미터 필터 ──────────────────────────────────────────

    @Test
    @DisplayName("category 파라미터 지정 시 해당 카테고리 조회 메서드 호출")
    void getFeed_categoryFilter_callsCategoryQuery() {
        mockInterests();
        mockKeywords();

        when(articleRepository.findFeedCandidatesByCategory(anyList(), eq(Category.IT), any(), any(), any()))
                .thenReturn(List.of());

        feedService.getFeed(ACCOUNT_ID, null, 20, Category.IT);

        verify(articleRepository).findFeedCandidatesByCategory(
                anyList(), eq(Category.IT), any(), any(), any());
        verify(articleRepository, never()).findFeedCandidates(anyList(), any(), any(), any());
    }

    // ── T6: DEEP 없고 BALANCED 있음 → isFallback=true, depth=balanced ──────

    @Test
    @DisplayName("DEEP 선호 + DEEP 슬롯 없음 + BALANCED 있음 → isFallback=true, depth=balanced")
    void getFeed_deepMissingBalancedExists_fallback() {
        mockInterests("IT");
        mockKeywords();

        ReadingPreference pref = buildReadingPref(SummaryDepth.DEEP);
        when(readingPreferenceRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(pref));

        Article article = buildArticle(1L, Category.IT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        mockCandidates(article);

        Summary balancedSummary = buildSummary(1L, SummaryDepth.BALANCED, SummarySlotStatus.COMPLETED, "balanced content");
        when(summaryRepository.findCompletedByArticleIdIn(any())).thenReturn(List.of(balancedSummary));

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        assertThat(response.articles()).hasSize(1);
        assertThat(response.articles().get(0).summary().isFallback()).isTrue();
        assertThat(response.articles().get(0).summary().depth()).isEqualTo("balanced");
        assertThat(response.articles().get(0).summary().text()).isEqualTo("balanced content");
    }

    // ── T7: 요약 슬롯 없음 → text=null, isFallback=false ───────────────────

    @Test
    @DisplayName("요약 슬롯 없으면 text=null, isFallback=false")
    void getFeed_noSummarySlots_textNullIsFallbackFalse() {
        mockInterests("POLITICS");
        mockKeywords();

        Article article = buildArticle(1L, Category.POLITICS, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        mockCandidates(article);

        // summaryRepository는 빈 목록 반환 (setUp에서 이미 stubbing)
        when(summaryRepository.findCompletedByArticleIdIn(any())).thenReturn(List.of());

        FeedResponse response = feedService.getFeed(ACCOUNT_ID, null, 20, null);

        assertThat(response.articles()).hasSize(1);
        assertThat(response.articles().get(0).summary().text()).isNull();
        assertThat(response.articles().get(0).summary().isFallback()).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void mockInterests(String... categories) {
        List<UserInterests> interests = java.util.Arrays.stream(categories)
                .map(cat -> {
                    UserInterests ui = new UserInterests();
                    ReflectionTestUtils.setField(ui, "category", cat);
                    return ui;
                })
                .toList();
        when(userInterestsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(interests);
    }

    private void mockKeywords(String... keywords) {
        List<FollowKeyword> followKeywords = java.util.Arrays.stream(keywords)
                .map(kw -> {
                    FollowKeyword fk = new FollowKeyword();
                    ReflectionTestUtils.setField(fk, "keyword", kw);
                    ReflectionTestUtils.setField(fk, "type", KeywordType.THEME);
                    return fk;
                })
                .toList();
        when(followKeywordRepository.findByAccountId(ACCOUNT_ID)).thenReturn(followKeywords);
    }

    private void mockCandidates(Article... articles) {
        when(articleRepository.findFeedCandidates(anyList(), any(), any(), any()))
                .thenReturn(java.util.Arrays.asList(articles));
    }

    private Article buildArticle(Long id, Category category, OffsetDateTime publishedAt) {
        return buildArticleWithTitle(id, "테스트 기사 " + id, category, publishedAt);
    }

    private Article buildArticleWithTitle(Long id, String title, Category category, OffsetDateTime publishedAt) {
        Article article = Article.builder()
                .normalizedUrl("https://example.com/article/" + id)
                .originalUrl("https://example.com/article/" + id)
                .title(title)
                .publishedAt(publishedAt)
                .firstCollectedAt(publishedAt)
                .expiresAt(publishedAt.plusDays(90))
                .build();
        ReflectionTestUtils.setField(article, "id", id);
        ReflectionTestUtils.setField(article, "category", category);
        ReflectionTestUtils.setField(article, "categoryStatus", ProcessingStatus.COMPLETED);
        return article;
    }

    private Summary buildSummary(Long articleId, SummaryDepth depth, SummarySlotStatus status, String content) {
        Article mockArticle = buildArticle(articleId, Category.IT, OffsetDateTime.now(ZoneOffset.UTC));
        Summary summary = Summary.builder()
                .article(mockArticle)
                .depth(depth)
                .build();
        ReflectionTestUtils.setField(summary, "id", articleId * 10L + depth.ordinal());
        ReflectionTestUtils.setField(summary, "status", status);
        ReflectionTestUtils.setField(summary, "content", content);
        return summary;
    }

    private ReadingPreference buildReadingPref(SummaryDepth depth) {
        ReadingPreference pref = new ReadingPreference();
        ReflectionTestUtils.setField(pref, "summaryDepth", depth);
        return pref;
    }
}
