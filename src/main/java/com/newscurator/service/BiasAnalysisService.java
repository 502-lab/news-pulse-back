package com.newscurator.service;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.client.ai.BiasAnalysisResult;
import com.newscurator.config.BiasProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.domain.enums.BiasStatus;
import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.AiTransientException;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.scheduler.BiasAnalysisClaimer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 편향 분석 파이프라인 서비스 (two-tx 오케스트레이션, research R-013).
 *
 * <p>{@link #processBatch()}는 자체 트랜잭션을 열지 않는다 — claim/persist는
 * {@link BiasAnalysisClaimer}의 별도 TX가 담당하고, 그 사이 Gemini HTTP 호출은 DB 락 밖에서 실행된다.
 */
@Service
public class BiasAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BiasAnalysisService.class);

    private final BiasAnalysisClaimer claimer;
    private final BiasAnalysisRepository biasAnalysisRepository;
    private final ArticleRepository articleRepository;
    private final AiProvider aiProvider;
    private final BiasProperties biasProperties;

    public BiasAnalysisService(
            BiasAnalysisClaimer claimer,
            BiasAnalysisRepository biasAnalysisRepository,
            ArticleRepository articleRepository,
            AiProvider aiProvider,
            BiasProperties biasProperties) {
        this.claimer = claimer;
        this.biasAnalysisRepository = biasAnalysisRepository;
        this.articleRepository = articleRepository;
        this.aiProvider = aiProvider;
        this.biasProperties = biasProperties;
    }

    /**
     * 기사 수집 시 호출 — 멱등 PENDING 행 생성 (FR-001/FR-004).
     * UNIQUE(article_id) 위반 시 이미 존재하므로 조용히 무시한다.
     */
    @Transactional
    public void createPendingForArticle(Long articleId) {
        try {
            biasAnalysisRepository.save(BiasAnalysis.builder().articleId(articleId).build());
        } catch (DataIntegrityViolationException e) {
            log.debug("[BIAS] bias_analysis 행 이미 존재(멱등), articleId={}", articleId);
        }
    }

    /**
     * 배치 처리 (FR-002/FR-003/FR-011).
     * Phase1 claim(별도 TX) → Phase2 Gemini 호출(락 밖) → Phase3 persistResult(별도 TX).
     */
    public void processBatch() {
        List<BiasAnalysis> batch = claimer.claimBatch(biasProperties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.info("[BIAS] 배치 처리 시작, size={}", batch.size());

        int done = 0;
        int failed = 0;
        for (BiasAnalysis row : batch) {
            try {
                analyzeAndComplete(row);
                done++;
            } catch (AiTransientException e) {
                // 일시 오류(429/5xx/타임아웃): 재시도 카운트 증가 후 배치 조기 중단
                row.incrementAttemptWithBackoff(
                        biasProperties.backoffAttempt1Minutes(),
                        biasProperties.backoffAttempt2Minutes());
                claimer.persistResult(row);
                failed++;
                log.warn("[BIAS] 일시 오류로 배치 조기 중단, articleId={}, attempt={}: {}",
                        row.getArticleId(), row.getAttemptCount(), e.getMessage());
                break;
            } catch (AiProviderException e) {
                row.incrementAttemptWithBackoff(
                        biasProperties.backoffAttempt1Minutes(),
                        biasProperties.backoffAttempt2Minutes());
                claimer.persistResult(row);
                failed++;
                log.warn("[BIAS] 분석 실패, articleId={}, attempt={}, status={}: {}",
                        row.getArticleId(), row.getAttemptCount(), row.getStatus(), e.getMessage());
            }
        }
        log.info("[BIAS] 배치 처리 종료, done={}, failed={}", done, failed);
    }

    /**
     * One-shot 복구 (FR-003): FAILED·attempt_count=3·failed_at+6h 경과 1건을 1회 재시도.
     * 성공 → completeOneShot(DONE), 실패 → failTerminal(attempt_count=4 terminal, 재발화 0).
     */
    public void recoverOneShotFailed() {
        Optional<BiasAnalysis> candidate = claimer.claimOneShotRecovery();
        if (candidate.isEmpty()) {
            return;
        }
        BiasAnalysis row = candidate.get();
        try {
            BiasAnalysisResult result = analyze(row);
            row.completeOneShot(result.value(), toArray(result));
            claimer.persistResult(row);
            log.info("[BIAS] one-shot 복구 성공 → DONE, articleId={}", row.getArticleId());
        } catch (AiProviderException e) {
            // AiTransientException은 AiProviderException의 하위 — 둘 다 여기서 terminal 처리
            row.failTerminal();
            claimer.persistResult(row);
            log.warn("[BIAS] one-shot 복구 실패 → terminal FAILED(attempt=4), articleId={}: {}",
                    row.getArticleId(), e.getMessage());
        }
    }

    /** Backfill: 최근 90일 기사 PENDING 일괄 생성 (멱등). 정상 claimer가 rate-safe 드레인. */
    @Transactional
    public long backfill() {
        int created = biasAnalysisRepository.backfillPending();
        log.info("[BIAS] backfill 완료, created={}", created);
        return created;
    }

    /**
     * 편향 칩 API (FR-009): 기사의 BiasAnalysis 행을 조회해 점수 응답으로 반환한다.
     * 행이 없으면 404. 미완료/실패면 value·keywords는 null이고 status만 채워 200으로 반환한다.
     */
    @Transactional(readOnly = true)
    public BiasScoreResponse getBiasForArticle(Long articleId) {
        BiasAnalysis row = biasAnalysisRepository
                .findByArticleId(articleId)
                .orElseThrow(() -> new ResourceNotFoundException("BiasAnalysis", articleId));
        return toResponse(row);
    }

    /**
     * BiasAnalysis → BiasScoreResponse 매핑 (피드/상세/칩 공용).
     * row가 null이면 null 반환(필드는 존재, 값만 null). DONE이면 value·keywords 포함,
     * 그 외 상태는 value·keywords=null + status만.
     */
    public static BiasScoreResponse toResponse(BiasAnalysis row) {
        if (row == null) {
            return null;
        }
        if (row.getStatus() == BiasStatus.DONE) {
            List<String> keywords = row.getRationaleKeywords() == null
                    ? null
                    : List.of(row.getRationaleKeywords());
            return new BiasScoreResponse(row.getValue(), keywords, BiasStatus.DONE.name());
        }
        return new BiasScoreResponse(null, null, row.getStatus().name());
    }

    /** SC-001 일일 emit (FR-012): 24h DONE 비율 + 당일 FAILED 건수 구조적 로그. */
    public void emitDailySlaMetrics() {
        Double doneRatio = biasAnalysisRepository.computeDoneRatio7Day();
        long failedToday = biasAnalysisRepository.countFailedToday();
        log.info("[BIAS-SLA] done_ratio={}, failed_today={}",
                doneRatio == null ? "n/a" : String.format("%.2f", doneRatio), failedToday);
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private void analyzeAndComplete(BiasAnalysis row) {
        BiasAnalysisResult result = analyze(row);
        row.complete(result.value(), toArray(result));
        claimer.persistResult(row);
    }

    private BiasAnalysisResult analyze(BiasAnalysis row) {
        Article article = articleRepository
                .findById(row.getArticleId())
                .orElseThrow(() -> new AiProviderException(
                        "기사 없음, articleId=" + row.getArticleId()));
        return aiProvider.analyzeBias(article.getTitle(), "");
    }

    private static String[] toArray(BiasAnalysisResult result) {
        return result.rationaleKeywords().toArray(new String[0]);
    }
}
