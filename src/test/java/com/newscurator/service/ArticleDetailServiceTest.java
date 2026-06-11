package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
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
class ArticleDetailServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @Mock
    private AiProvider aiProvider;

    private SummaryService summaryService;
    private ArticleDetailService articleDetailService;

    @BeforeEach
    void setUp() {
        AiProperties.DeepRetryProperties deepRetry = new AiProperties.DeepRetryProperties(60, 5);
        AiProperties aiProperties = new AiProperties(10, 3, 0L, deepRetry);
        summaryService = new SummaryService(aiProperties);
        articleDetailService = new ArticleDetailService(
                articleRepository, summaryRepository, aiProvider, summaryService);
    }

    @Test
    @DisplayName("404: 기사 미존재 시 ArticleNotFoundException")
    void getDetail_articleNotFound_throwsNotFoundException() {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> articleDetailService.getDetail(999L))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    @DisplayName("DEEP NOT_GENERATED → 최초 조회 시 AI 호출 후 COMPLETED")
    void getDetail_deepNotGenerated_callsAiAndCompletes() {
        Article article = buildArticle(1L);
        Summary deepSlot = buildSummarySlot(SummaryDepth.DEEP, SummarySlotStatus.NOT_GENERATED, null, 0);
        Summary balancedSlot = buildCompletedSlot(SummaryDepth.BALANCED, "균형 요약");
        Summary briefSlot = buildCompletedSlot(SummaryDepth.BRIEF, "핵심 요약");

        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        when(summaryRepository.findByArticleId(1L))
                .thenReturn(List.of(balancedSlot, briefSlot, deepSlot));
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.DEEP)))
                .thenReturn("심층 요약 내용");

        ArticleDetailResponse response = articleDetailService.getDetail(1L);

        verify(aiProvider).summarize(anyString(), anyString(), eq(SummaryDepth.DEEP));
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("DEEP FAILED + 쿨다운 미경과 → AI 재호출 없음, FAILED 상태 그대로 반환 (CHK022)")
    void getDetail_deepFailedCooldownNotElapsed_returnsFailedWithoutAiCall() {
        Article article = buildArticle(2L);
        // 30분 전 실패, 쿨다운 60분 미경과
        Summary deepSlot = buildSummarySlot(
                SummaryDepth.DEEP, SummarySlotStatus.FAILED,
                OffsetDateTime.now().minusMinutes(30), 1);
        Summary balancedSlot = buildCompletedSlot(SummaryDepth.BALANCED, "균형 요약");
        Summary briefSlot = buildCompletedSlot(SummaryDepth.BRIEF, "핵심 요약");

        when(articleRepository.findById(2L)).thenReturn(Optional.of(article));
        when(summaryRepository.findByArticleId(2L))
                .thenReturn(List.of(balancedSlot, briefSlot, deepSlot));

        ArticleDetailResponse response = articleDetailService.getDetail(2L);

        verify(aiProvider, never()).summarize(anyString(), anyString(), eq(SummaryDepth.DEEP));
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("DEEP AI 오류 → Summary.status=FAILED + last_attempt_at 갱신 + 200 반환 (CHK022)")
    void getDetail_deepAiError_returnsFailed200() {
        Article article = buildArticle(3L);
        Summary deepSlot = buildSummarySlot(SummaryDepth.DEEP, SummarySlotStatus.NOT_GENERATED, null, 0);
        Summary balancedSlot = buildCompletedSlot(SummaryDepth.BALANCED, "균형 요약");
        Summary briefSlot = buildCompletedSlot(SummaryDepth.BRIEF, "핵심 요약");

        when(articleRepository.findById(3L)).thenReturn(Optional.of(article));
        when(summaryRepository.findByArticleId(3L))
                .thenReturn(List.of(balancedSlot, briefSlot, deepSlot));
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.DEEP)))
                .thenThrow(new AiProviderException("AI error"));

        // AI 오류에도 불구하고 200 반환 (예외 전파 없음)
        assertThatNoException().isThrownBy(() -> articleDetailService.getDetail(3L));

        // DEEP 슬롯 FAILED 처리
        verify(deepSlot).failDeepSlot();
    }

    @Test
    @DisplayName("DEEP FAILED + retry 허용 → AI 재시도 (CHK022 data-model 정합)")
    void getDetail_deepFailedRetryAllowed_retriesAi() {
        Article article = buildArticle(4L);
        // 70분 전 실패, 쿨다운 경과 + retryCount < limit
        Summary deepSlot = buildSummarySlot(
                SummaryDepth.DEEP, SummarySlotStatus.FAILED,
                OffsetDateTime.now().minusMinutes(70), 2);
        Summary balancedSlot = buildCompletedSlot(SummaryDepth.BALANCED, "균형 요약");
        Summary briefSlot = buildCompletedSlot(SummaryDepth.BRIEF, "핵심 요약");

        when(articleRepository.findById(4L)).thenReturn(Optional.of(article));
        when(summaryRepository.findByArticleId(4L))
                .thenReturn(List.of(balancedSlot, briefSlot, deepSlot));
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.DEEP)))
                .thenReturn("심층 재시도 요약");

        articleDetailService.getDetail(4L);

        verify(aiProvider).summarize(anyString(), anyString(), eq(SummaryDepth.DEEP));
    }

    private Article buildArticle(Long id) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(id);
        when(article.getTitle()).thenReturn("Test Article " + id);
        when(article.getCategory()).thenReturn(Category.IT);
        when(article.getCategoryStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(article.getPublishedAt()).thenReturn(OffsetDateTime.now());
        when(article.getFirstCollectedAt()).thenReturn(OffsetDateTime.now());
        return article;
    }

    private Summary buildSummarySlot(
            SummaryDepth depth, SummarySlotStatus status, OffsetDateTime lastAttemptAt, int retryCount) {
        Summary summary = mock(Summary.class);
        when(summary.getDepth()).thenReturn(depth);
        when(summary.getStatus()).thenReturn(status);
        when(summary.getLastAttemptAt()).thenReturn(lastAttemptAt);
        when(summary.getRetryCount()).thenReturn(retryCount);
        when(summary.getContent()).thenReturn(null);
        return summary;
    }

    private Summary buildCompletedSlot(SummaryDepth depth, String content) {
        Summary summary = mock(Summary.class);
        when(summary.getDepth()).thenReturn(depth);
        when(summary.getStatus()).thenReturn(SummarySlotStatus.COMPLETED);
        when(summary.getContent()).thenReturn(content);
        when(summary.getGeneratedAt()).thenReturn(OffsetDateTime.now());
        return summary;
    }
}
