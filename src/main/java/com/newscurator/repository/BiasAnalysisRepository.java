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
