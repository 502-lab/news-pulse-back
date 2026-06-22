package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.keyword.KeywordExtractor;
import com.newscurator.config.TrendProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.TrendKeywordSlotRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** T018: TrendAggregationService 단위 — 본문 게이팅(COMPLETED+요약 vs 제목만) + BALANCED null 방어. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrendAggregationServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private ArticleKeywordRepository articleKeywordRepository;
    @Mock private TrendKeywordSlotRepository trendKeywordSlotRepository;
    @Mock private KeywordExtractor keywordExtractor;

    private TrendAggregationService service;

    private static final TrendProperties PROPS =
            new TrendProperties(1, 24, 90, 2, 1, 25, 1, new TrendProperties.Cooccurrence(2, 2));

    @BeforeEach
    void setUp() {
        service = new TrendAggregationService(
                articleRepository, summaryRepository, articleKeywordRepository,
                trendKeywordSlotRepository, keywordExtractor, PROPS);
        when(keywordExtractor.extractNouns(any())).thenReturn(Set.of("x"));
    }

    private Article article(long id, String title) {
        Article a = Article.builder()
                .normalizedUrl("u" + id).originalUrl("u" + id).title(title)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build();
        return a;
    }

    // 실 Summary 객체(중첩 stubbing 회피). complete()로 content 세팅.
    private Summary balanced(Article a, String content) {
        Summary s = Summary.builder().article(a).depth(SummaryDepth.BALANCED).build();
        s.complete(content); // content=null/blank/정상 그대로 반영
        return s;
    }

    @Test
    @DisplayName("COMPLETED + BALANCED content 존재 → 제목+요약으로 추출")
    void completed_withBalanced_usesTitlePlusSummary() {
        Article a = article(1, "제목1");
        a.completeSummary(); // summary_status=COMPLETED
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of(a));
        when(summaryRepository.findByArticleIdAndDepth(a.getId(), SummaryDepth.BALANCED))
                .thenReturn(Optional.of(balanced(a, "금리 인상 내용")));

        service.aggregate();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keywordExtractor).extractNouns(cap.capture());
        assertThat(cap.getValue()).isEqualTo("제목1 금리 인상 내용");
    }

    @Test
    @DisplayName("COMPLETED 이지만 BALANCED 행 부재 → 제목만 (NPE 없음)")
    void completed_noBalancedRow_titleOnly() {
        Article a = article(2, "제목2");
        a.completeSummary();
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of(a));
        when(summaryRepository.findByArticleIdAndDepth(a.getId(), SummaryDepth.BALANCED))
                .thenReturn(Optional.empty());

        service.aggregate();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keywordExtractor).extractNouns(cap.capture());
        assertThat(cap.getValue()).isEqualTo("제목2");
    }

    @Test
    @DisplayName("COMPLETED + BALANCED content=null → 제목만 (NPE 없음)")
    void completed_balancedNull_titleOnly() {
        Article a = article(3, "제목3");
        a.completeSummary();
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of(a));
        when(summaryRepository.findByArticleIdAndDepth(a.getId(), SummaryDepth.BALANCED))
                .thenReturn(Optional.of(balanced(a, null)));

        service.aggregate();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keywordExtractor).extractNouns(cap.capture());
        assertThat(cap.getValue()).isEqualTo("제목3");
    }

    @Test
    @DisplayName("COMPLETED + BALANCED content=blank → 제목만")
    void completed_balancedBlank_titleOnly() {
        Article a = article(4, "제목4");
        a.completeSummary();
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of(a));
        when(summaryRepository.findByArticleIdAndDepth(a.getId(), SummaryDepth.BALANCED))
                .thenReturn(Optional.of(balanced(a, "   ")));

        service.aggregate();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keywordExtractor).extractNouns(cap.capture());
        assertThat(cap.getValue()).isEqualTo("제목4");
    }

    @Test
    @DisplayName("FAILED → 제목만 (요약 조회 안 함)")
    void failed_titleOnly() {
        Article a = article(5, "제목5");
        a.failSummary(); // summary_status=FAILED
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of(a));

        service.aggregate();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keywordExtractor).extractNouns(cap.capture());
        assertThat(cap.getValue()).isEqualTo("제목5");
        verify(summaryRepository, never()).findByArticleIdAndDepth(any(), any());
    }

    @Test
    @DisplayName("후보 없음 → 추출 0, 슬롯 UPSERT는 호출")
    void noCandidates_stillUpserts() {
        when(articleRepository.findTrendExtractionCandidates(any(), any())).thenReturn(List.of());

        service.aggregate();

        verify(keywordExtractor, never()).extractNouns(any());
        verify(trendKeywordSlotRepository).upsertSlots(any());
    }
}
