package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.response.PipelineStatsResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.SummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PipelineStatsServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleSourceRepository articleSourceRepository;

    @Mock
    private SummaryRepository summaryRepository;

    private PipelineStatsService pipelineStatsService;

    @BeforeEach
    void setUp() {
        pipelineStatsService = new PipelineStatsService(
                articleRepository, articleSourceRepository);
    }

    @Test
    @DisplayName("articlesCollectedToday: first_collected_at=today 기준 카운트")
    void getStats_articlesCollectedToday() {
        when(articleRepository.countCollectedToday(any())).thenReturn(100L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.PENDING)).thenReturn(10L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.FAILED)).thenReturn(5L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.PENDING)).thenReturn(20L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.FAILED)).thenReturn(3L);
        when(articleRepository.countByCategoryStatusIn(anyList())).thenReturn(90L);
        when(articleRepository.countSummaryCompleted()).thenReturn(80L);
        when(articleSourceRepository.countMergeToday(any())).thenReturn(5L);

        PipelineStatsResponse stats = pipelineStatsService.getStats();

        assertThat(stats.articlesCollectedToday()).isEqualTo(100L);
    }

    @Test
    @DisplayName("summaryCompletionRate: COMPLETED/total×100 계산")
    void getStats_summaryCompletionRate() {
        when(articleRepository.countCollectedToday(any())).thenReturn(100L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.PENDING)).thenReturn(0L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.FAILED)).thenReturn(0L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.PENDING)).thenReturn(0L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.FAILED)).thenReturn(0L);
        when(articleRepository.countByCategoryStatusIn(anyList())).thenReturn(100L);
        when(articleRepository.countSummaryCompleted()).thenReturn(80L);
        when(articleSourceRepository.countMergeToday(any())).thenReturn(0L);

        PipelineStatsResponse stats = pipelineStatsService.getStats();

        assertThat(stats.summaryCompletionRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("summaryCompletionRate: 분모 0 시 0% 반환")
    void getStats_summaryCompletionRate_zeroDenominator() {
        when(articleRepository.countCollectedToday(any())).thenReturn(0L);
        when(articleRepository.countByCategoryStatus(any())).thenReturn(0L);
        when(articleRepository.countBySummaryStatus(any())).thenReturn(0L);
        when(articleRepository.countByCategoryStatusIn(anyList())).thenReturn(0L);
        when(articleRepository.countSummaryCompleted()).thenReturn(0L);
        when(articleSourceRepository.countMergeToday(any())).thenReturn(0L);

        PipelineStatsResponse stats = pipelineStatsService.getStats();

        assertThat(stats.summaryCompletionRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("mergeCount: is_merge=true today 기준")
    void getStats_mergeCount() {
        when(articleRepository.countCollectedToday(any())).thenReturn(10L);
        when(articleRepository.countByCategoryStatus(any())).thenReturn(0L);
        when(articleRepository.countBySummaryStatus(any())).thenReturn(0L);
        when(articleRepository.countByCategoryStatusIn(anyList())).thenReturn(10L);
        when(articleRepository.countSummaryCompleted()).thenReturn(10L);
        when(articleSourceRepository.countMergeToday(any())).thenReturn(3L);

        PipelineStatsResponse stats = pipelineStatsService.getStats();

        assertThat(stats.mergeCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("pipelineStatus: 4개 카운트 필드 포함")
    void getStats_pipelineStatus_allFieldsPresent() {
        when(articleRepository.countCollectedToday(any())).thenReturn(50L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.PENDING)).thenReturn(15L);
        when(articleRepository.countByCategoryStatus(ProcessingStatus.FAILED)).thenReturn(5L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.PENDING)).thenReturn(25L);
        when(articleRepository.countBySummaryStatus(ProcessingStatus.FAILED)).thenReturn(2L);
        when(articleRepository.countByCategoryStatusIn(anyList())).thenReturn(45L);
        when(articleRepository.countSummaryCompleted()).thenReturn(40L);
        when(articleSourceRepository.countMergeToday(any())).thenReturn(1L);

        PipelineStatsResponse stats = pipelineStatsService.getStats();

        assertThat(stats.pipelineStatus().categoryPending()).isEqualTo(15L);
        assertThat(stats.pipelineStatus().categoryFailed()).isEqualTo(5L);
        assertThat(stats.pipelineStatus().summaryPending()).isEqualTo(25L);
        assertThat(stats.pipelineStatus().summaryFailed()).isEqualTo(2L);
    }
}
