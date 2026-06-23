package com.newscurator.repository;

import com.newscurator.domain.BiasAnalysis;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiasAnalysisRepository extends JpaRepository<BiasAnalysis, Long> {

    Optional<BiasAnalysis> findByArticleId(Long articleId);

    // ── 008 US2 어드민 모니터링(편향) ──

    /** 상태별 카운트(네이티브 — enum 비결합). status ∈ PENDING/PROCESSING/DONE/FAILED. */
    @Query(value = "SELECT COUNT(*) FROM bias_analysis WHERE status = :status", nativeQuery = true)
    long countByStatusValue(@Param("status") String status);

    /**
     * ★ 어드민 편향 뷰: 분석 완료(DONE) 기사 수 — admin_hidden_at 필터 안 함(어드민은 hidden 포함).
     * 일반 사용자 경로와 달리 숨김 기사의 분석도 집계에 포함된다.
     */
    @Query(
            value =
                    "SELECT COUNT(DISTINCT a.id) FROM bias_analysis ba"
                            + " JOIN articles a ON a.id = ba.article_id WHERE ba.status = 'DONE'",
            nativeQuery = true)
    long countAnalyzedArticlesIncludingHidden();

    List<BiasAnalysis> findAllByArticleIdIn(List<Long> articleIds);

    // Claimer: PENDING + lease 만료된 PROCESSING(stuck) 행 회수, SKIP LOCKED
    // two-tx 모델(005와 동일하게 claim TX 커밋 후 락 해제 → Gemini 호출은 락 밖):
    //   - 정상 처리 중 행: claim()이 next_retry_at = NOW()+lease(미래)로 세팅 → next_retry_at > NOW() → 제외
    //   - 크래시로 고아가 된 PROCESSING 행: lease 경과 후 next_retry_at <= NOW() → 재claim되어 회수
    @Query(value = """
            SELECT * FROM bias_analysis
            WHERE status IN ('PENDING', 'PROCESSING') AND next_retry_at <= NOW()
            ORDER BY next_retry_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BiasAnalysis> lockAndClaimPending(@Param("limit") int limit);

    // One-shot 복구 대상: 3회 소진 FAILED + failed_at + 6h 경과
    @Query(value = """
            SELECT * FROM bias_analysis
            WHERE status = 'FAILED' AND attempt_count = 3
              AND failed_at + INTERVAL '6 hours' <= NOW()
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<BiasAnalysis> lockOneShotRecoveryCandidate();

    // SC-001: done_ratio 측정 (7일 롤링, 수집 후 24h 경과 기사 모집단 중 status=DONE 비율)
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE ba.status = 'DONE') * 100.0 / NULLIF(COUNT(*), 0)
            FROM bias_analysis ba
            JOIN articles a ON ba.article_id = a.id
            WHERE a.first_collected_at < NOW() - INTERVAL '24 hours'
              AND a.first_collected_at >= NOW() - INTERVAL '7 days'
            """, nativeQuery = true)
    Double computeDoneRatio7Day();

    // 당일 FAILED 전환(3회 소진) 건수
    @Query(value = """
            SELECT COUNT(*) FROM bias_analysis
            WHERE status = 'FAILED' AND attempt_count = 3
              AND CAST(failed_at AS DATE) = CURRENT_DATE
            """, nativeQuery = true)
    long countFailedToday();

    // 출처 편향 집계 (FR-006): 단일 GROUP-less 집계 1행 반환 [0]=avg(BigDecimal|null), [1]=count(Long).
    // article_sources(source_id) JOIN — idx_article_sources_source_id(V13) 사용. 단일 쿼리(N+1 아님).
    @Query(value = """
            SELECT AVG(ba.value)::NUMERIC(5,2) AS bias_value,
                   COUNT(*)                     AS article_count
            FROM bias_analysis ba
                     JOIN article_sources aso ON ba.article_id = aso.article_id
            WHERE aso.source_id = :sourceId
              AND ba.status = 'DONE'
              AND ba.analyzed_at >= NOW() - INTERVAL '90 days'
            """, nativeQuery = true)
    List<Object[]> aggregateOutletBias(@Param("sourceId") Long sourceId);

    // 전체 편향 스펙트럼 (FR-007/FR-008): 단일 집계 1행.
    // 버킷 진보[-100,-34]/중립[-33,+33]/보수[+34,+100], inclusive 정수범위.
    // NULLIF(COUNT,0): 0건일 때 division-by-zero 방지 → 비율 NULL.
    @Query(value = """
            SELECT AVG(value)::NUMERIC(5,2) AS weighted_average,
                   100.0 * COUNT(*) FILTER (WHERE value BETWEEN -100 AND -34)
                       / NULLIF(COUNT(*), 0) AS liberal_percent,
                   100.0 * COUNT(*) FILTER (WHERE value BETWEEN -33 AND 33)
                       / NULLIF(COUNT(*), 0) AS neutral_percent,
                   100.0 * COUNT(*) FILTER (WHERE value BETWEEN 34 AND 100)
                       / NULLIF(COUNT(*), 0) AS conservative_percent,
                   COUNT(*)                  AS total_count
            FROM bias_analysis
            WHERE status = 'DONE'
            """, nativeQuery = true)
    List<Object[]> aggregateSpectrum();

    // Backfill: 최근 90일 기사 중 bias_analysis 미존재 기사에 PENDING 일괄 생성 (멱등)
    @Modifying
    @Query(value = """
            INSERT INTO bias_analysis (article_id, status, next_retry_at, created_at, updated_at)
            SELECT id, 'PENDING', NOW(), NOW(), NOW()
            FROM articles
            WHERE first_collected_at >= NOW() - INTERVAL '90 days'
            ON CONFLICT (article_id) DO NOTHING
            """, nativeQuery = true)
    int backfillPending();
}
