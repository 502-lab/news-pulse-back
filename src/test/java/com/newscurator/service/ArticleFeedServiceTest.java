package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.config.FeedProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.request.FeedRequest;
import com.newscurator.dto.response.ArticleFeedResponse;
import com.newscurator.repository.ArticleRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleFeedServiceTest {

    @Mock private ArticleRepository articleRepository;

    private ArticleFeedService articleFeedService;

    @BeforeEach
    void setUp() {
        FeedProperties feedProperties = new FeedProperties(20, 100);
        articleFeedService = new ArticleFeedService(articleRepository, feedProperties);
    }

    @Test
    @DisplayName("빈 피드 응답: data=[], nextCursor=null, hasMore=false, size=0 (CHK030)")
    void getFeed_emptyResult_returnsEmptyResponse() {
        when(articleRepository.findFeedPage(anyList(), any())).thenReturn(List.of());

        FeedRequest request = new FeedRequest(null, 20, null);
        ArticleFeedResponse response = articleFeedService.getFeed(request);

        assertThat(response.data()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("size 150 요청 → 100으로 clamp (내부 조회는 101건으로 hasMore 판별)")
    void getFeed_sizeOver100_clampedTo100() {
        when(articleRepository.findFeedPage(anyList(), any())).thenReturn(List.of());

        FeedRequest request = new FeedRequest(null, 150, null);
        articleFeedService.getFeed(request);

        // 서비스는 size+1 (101)건 조회하여 hasMore를 판별
        verify(articleRepository).findFeedPage(anyList(), argThat(p -> p.getPageSize() == 101));
    }

    @Test
    @DisplayName("FAILED 카테고리 → API 응답에서 OTHER로 매핑")
    void getFeed_failedCategory_mappedToOther() {
        Article failedArticle = buildArticle(1L, null, ProcessingStatus.FAILED);
        when(articleRepository.findFeedPage(anyList(), any()))
                .thenReturn(List.of(failedArticle));

        FeedRequest request = new FeedRequest(null, 20, null);
        ArticleFeedResponse response = articleFeedService.getFeed(request);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).category()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("카테고리 필터 적용")
    void getFeed_withCategoryFilter_callsCategoryQuery() {
        when(articleRepository.findFeedPageByCategory(anyList(), eq(Category.IT), any()))
                .thenReturn(List.of());

        FeedRequest request = new FeedRequest(null, 20, Category.IT);
        articleFeedService.getFeed(request);

        verify(articleRepository).findFeedPageByCategory(anyList(), eq(Category.IT), any());
    }

    @Test
    @DisplayName("커서 기반 페이지네이션: 요청 size만큼 반환")
    void getFeed_fullPage_returnsSizeItems() {
        List<Article> articles = buildArticles(20);
        when(articleRepository.findFeedPage(anyList(), any())).thenReturn(articles);

        FeedRequest request = new FeedRequest(null, 20, null);
        ArticleFeedResponse response = articleFeedService.getFeed(request);

        assertThat(response.data()).hasSize(20);
    }

    @Test
    @DisplayName("feed_visible=true, category_status∈{COMPLETED,FAILED} 조건 적용")
    void getFeed_queriesWithCorrectStatuses() {
        when(articleRepository.findFeedPage(anyList(), any())).thenReturn(List.of());

        FeedRequest request = new FeedRequest(null, 20, null);
        articleFeedService.getFeed(request);

        verify(articleRepository)
                .findFeedPage(
                        argThat(
                                statuses ->
                                        statuses.contains(ProcessingStatus.COMPLETED)
                                                && statuses.contains(ProcessingStatus.FAILED)),
                        any());
    }

    private Article buildArticle(Long id, Category category, ProcessingStatus status) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(id);
        when(article.getTitle()).thenReturn("Test Title");
        when(article.getCategory()).thenReturn(category);
        when(article.getCategoryStatus()).thenReturn(status);
        when(article.getPublishedAt()).thenReturn(OffsetDateTime.now());
        when(article.getFirstCollectedAt()).thenReturn(OffsetDateTime.now());
        return article;
    }

    private List<Article> buildArticles(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> buildArticle((long) i, Category.IT, ProcessingStatus.COMPLETED))
                .toList();
    }
}
