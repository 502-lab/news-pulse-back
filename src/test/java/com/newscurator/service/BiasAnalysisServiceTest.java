package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.client.ai.BiasAnalysisResult;
import com.newscurator.config.BiasProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.domain.enums.BiasStatus;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.AiTransientException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.scheduler.BiasAnalysisClaimer;
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
import org.springframework.dao.DataIntegrityViolationException;

/** T017: BiasAnalysisService 단위 테스트 (AiProvider/claimer mock). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BiasAnalysisServiceTest {

    @Mock private BiasAnalysisClaimer claimer;
    @Mock private BiasAnalysisRepository biasAnalysisRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private AiProvider aiProvider;

    private BiasAnalysisService service;

    private static final BiasProperties PROPS =
            new BiasProperties(60000L, 10, 3600000L, 5, 30, 5);

    @BeforeEach
    void setUp() {
        service = new BiasAnalysisService(
                claimer, biasAnalysisRepository, articleRepository, aiProvider, PROPS);
    }

    private Article article(long id) {
        Article a = Article.builder()
                .normalizedUrl("u" + id)
                .originalUrl("u" + id)
                .title("title " + id)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build();
        // id는 영속화 전이라 null — articleRepository.findById mock으로 우회
        return a;
    }

    private BiasAnalysis pendingRow(long articleId) {
        return BiasAnalysis.builder().articleId(articleId).build();
    }

    @Test
    @DisplayName("정상 처리: complete → DONE + persistResult")
    void processBatch_success_completesDone() {
        BiasAnalysis row = pendingRow(1L);
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(row));
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L)));
        when(aiProvider.analyzeBias(any(), any()))
                .thenReturn(new BiasAnalysisResult(-45, List.of("k1", "k2")));

        service.processBatch();

        assertThat(row.getStatus()).isEqualTo(BiasStatus.DONE);
        assertThat(row.getValue()).isEqualTo(-45);
        assertThat(row.getRationaleKeywords()).containsExactly("k1", "k2");
        verify(claimer).persistResult(row);
    }

    @Test
    @DisplayName("결정적 실패: incrementAttemptWithBackoff → attempt=1, PENDING")
    void processBatch_providerError_backsOff() {
        BiasAnalysis row = pendingRow(2L);
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(row));
        when(articleRepository.findById(2L)).thenReturn(Optional.of(article(2L)));
        when(aiProvider.analyzeBias(any(), any())).thenThrow(new AiProviderException("bad"));

        service.processBatch();

        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(BiasStatus.PENDING);
        verify(claimer).persistResult(row);
    }

    @Test
    @DisplayName("일시 오류: increment + 배치 조기 중단(두 번째 행 미처리)")
    void processBatch_transient_breaks() {
        BiasAnalysis row1 = pendingRow(3L);
        BiasAnalysis row2 = pendingRow(4L);
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(row1, row2));
        when(articleRepository.findById(3L)).thenReturn(Optional.of(article(3L)));
        when(aiProvider.analyzeBias(any(), any()))
                .thenThrow(new AiTransientException("429", new RuntimeException()));

        service.processBatch();

        assertThat(row1.getAttemptCount()).isEqualTo(1);
        assertThat(row2.getStatus()).isEqualTo(BiasStatus.PENDING); // 미처리(claim 직후 상태)
        assertThat(row2.getAttemptCount()).isZero();
        verify(claimer, times(1)).persistResult(any());
    }

    @Test
    @DisplayName("3회 소진 → FAILED + failed_at 기록")
    void processBatch_threeFailures_failed() {
        BiasAnalysis row = pendingRow(5L);
        when(articleRepository.findById(5L)).thenReturn(Optional.of(article(5L)));
        when(aiProvider.analyzeBias(any(), any())).thenThrow(new AiProviderException("bad"));

        for (int i = 0; i < 3; i++) {
            when(claimer.claimBatch(anyInt())).thenReturn(List.of(row));
            service.processBatch();
        }

        assertThat(row.getAttemptCount()).isEqualTo(3);
        assertThat(row.getStatus()).isEqualTo(BiasStatus.FAILED);
        assertThat(row.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("createPendingForArticle 멱등: UNIQUE 위반 삼킴")
    void createPending_idempotent() {
        when(biasAnalysisRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> service.createPendingForArticle(9L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SLA emit: null/정상 비율 모두 예외 없이 로깅")
    void emitSla_noException() {
        when(biasAnalysisRepository.computeDoneRatio7Day()).thenReturn(97.0);
        when(biasAnalysisRepository.countFailedToday()).thenReturn(3L);

        assertThatCode(() -> service.emitDailySlaMetrics()).doesNotThrowAnyException();
    }
}
