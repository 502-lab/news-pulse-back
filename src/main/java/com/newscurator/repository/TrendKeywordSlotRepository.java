package com.newscurator.repository;

import com.newscurator.domain.TrendKeywordSlot;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendKeywordSlotRepository
        extends JpaRepository<TrendKeywordSlot, TrendKeywordSlot.Pk> {

    /**
     * 윈도우 내 기사를 (slot_start=date_trunc('hour'), category, term)로 멱등 집계 UPSERT.
     * article_count = 해당 슬롯·카테고리에서 term 등장 기사 수(DISTINCT). 동일 입력 → 동일 결과.
     */
    @Modifying
    @Query(value = """
            INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
            SELECT date_trunc('hour', a.first_collected_at) AS slot_start,
                   COALESCE(a.category, 'OTHER')            AS category,
                   ak.term                                  AS term,
                   COUNT(DISTINCT a.id)                     AS article_count,
                   NOW()
            FROM article_keyword ak
                     JOIN articles a ON a.id = ak.article_id
            WHERE a.first_collected_at >= :windowStart
            GROUP BY 1, 2, 3
            ON CONFLICT (slot_start, category, term)
                DO UPDATE SET article_count = EXCLUDED.article_count, updated_at = NOW()
            """, nativeQuery = true)
    int upsertSlots(@Param("windowStart") Instant windowStart);

    /** 보존 정리: 90일 경과 슬롯 삭제. */
    @Modifying
    @Query(value = "DELETE FROM trend_keyword_slot WHERE slot_start < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
