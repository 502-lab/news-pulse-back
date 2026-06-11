package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.source.ArticleCandidate;
import com.newscurator.client.source.RssSourceAdapter;
import com.newscurator.client.source.SourceAdapter;
import com.newscurator.config.RetentionProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.SourceDailyUsageRepository;
import com.newscurator.repository.SourceRepository;
import com.newscurator.util.UrlNormalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
class SourceCollectionExecutorTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private ArticleSourceRepository articleSourceRepository;
    @Mock private SourceRepository sourceRepository;
    @Mock private SourceDailyUsageRepository sourceUsageRepository;
    @Mock private UrlNormalizer urlNormalizer;
    @Mock private RssSourceAdapter rssAdapter;

    private SourceCollectionExecutor executor;

    @BeforeEach
    void setUp() {
        RetentionProperties retentionProperties = new RetentionProperties(90, 7);
        List<SourceAdapter> adapters = List.of(rssAdapter);
        executor = new SourceCollectionExecutor(
                articleRepository,
                articleSourceRepository,
                sourceRepository,
                sourceUsageRepository,
                urlNormalizer,
                adapters,
                retentionProperties);
    }

    @Test
    @DisplayName("신규 URL 수집 시 기사 INSERT")
    void collectFromSource_newUrl_insertsArticle() {
        Source source = buildRssSource();
        ArticleCandidate candidate = buildCandidate("https://example.com/new/1");

        when(rssAdapter.supports(SourceAdapterType.RSS)).thenReturn(true);
        when(rssAdapter.fetchCandidates(source)).thenReturn(List.of(candidate));
        when(urlNormalizer.normalize(anyString())).thenReturn("https://example.com/new/1");
        when(articleRepository.findByNormalizedUrl(anyString())).thenReturn(Optional.empty());
        doNothing().when(sourceUsageRepository).upsertCallCount(anyLong(), any());
        when(sourceUsageRepository.selectCallCount(anyLong(), any())).thenReturn(1);

        executor.collectFromSource(source);

        verify(articleRepository).save(any(Article.class));
    }

    @Test
    @DisplayName("기존 URL 수집 시 ArticleSource upsert (is_merge=true)")
    void collectFromSource_existingUrl_upsertsMergeSource() {
        Source source = buildRssSource();
        ArticleCandidate candidate = buildCandidate("https://example.com/existing/1");
        Article existingArticle = buildExistingArticle("https://example.com/existing/1");

        when(rssAdapter.supports(SourceAdapterType.RSS)).thenReturn(true);
        when(rssAdapter.fetchCandidates(source)).thenReturn(List.of(candidate));
        when(urlNormalizer.normalize(anyString())).thenReturn("https://example.com/existing/1");
        when(articleRepository.findByNormalizedUrl(anyString()))
                .thenReturn(Optional.of(existingArticle));
        when(articleSourceRepository.existsByArticleIdAndSourceId(anyLong(), anyLong()))
                .thenReturn(false);
        doNothing().when(sourceUsageRepository).upsertCallCount(anyLong(), any());
        when(sourceUsageRepository.selectCallCount(anyLong(), any())).thenReturn(1);

        executor.collectFromSource(source);

        verify(articleRepository, never()).save(any(Article.class));
        verify(articleSourceRepository).save(any());
    }

    @Test
    @DisplayName("call_budget_daily 초과 시 해당 출처 수집 중단 (CHK010)")
    void collectFromSource_overBudget_stopsCollection() {
        Source source = buildRssSource();
        when(source.getCallBudgetDaily()).thenReturn(50);
        List<ArticleCandidate> candidates = List.of(
                buildCandidate("https://example.com/budget/1"),
                buildCandidate("https://example.com/budget/2"));

        when(rssAdapter.supports(SourceAdapterType.RSS)).thenReturn(true);
        when(rssAdapter.fetchCandidates(source)).thenReturn(candidates);
        when(urlNormalizer.normalize(anyString()))
                .thenReturn("https://example.com/budget/1")
                .thenReturn("https://example.com/budget/2");
        when(articleRepository.findByNormalizedUrl(anyString())).thenReturn(Optional.empty());
        doNothing().when(sourceUsageRepository).upsertCallCount(anyLong(), any());
        // 첫 번째 호출부터 예산 초과 (51 > 50)
        when(sourceUsageRepository.selectCallCount(anyLong(), any())).thenReturn(51);

        executor.collectFromSource(source);

        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("어댑터 fetchCandidates 예외 시 collectFromSource 밖으로 전파 → CollectionService가 recordFailure 처리")
    void collectFromSource_adapterThrowsException_propagatesException() {
        Source source = buildRssSource();
        when(rssAdapter.supports(SourceAdapterType.RSS)).thenReturn(true);
        when(rssAdapter.fetchCandidates(source))
                .thenThrow(new RuntimeException("parse failed"));

        assertThatThrownBy(() -> executor.collectFromSource(source))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("parse failed");
    }

    private Source buildRssSource() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("연합뉴스");
        when(source.getFeedUrl()).thenReturn("https://www.yna.co.kr/rss/news.xml");
        when(source.getAdapterType()).thenReturn(SourceAdapterType.RSS);
        when(source.isActive()).thenReturn(true);
        when(source.getCallBudgetDaily()).thenReturn(1000);
        return source;
    }

    private ArticleCandidate buildCandidate(String url) {
        return new ArticleCandidate(url, "Test Title", null, OffsetDateTime.now(), null);
    }

    private Article buildExistingArticle(String normalizedUrl) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(1L);
        when(article.getNormalizedUrl()).thenReturn(normalizedUrl);
        return article;
    }
}
