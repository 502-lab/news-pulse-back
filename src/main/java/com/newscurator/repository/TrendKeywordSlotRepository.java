package com.newscurator.repository;

import com.newscurator.domain.TrendKeywordSlot;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendKeywordSlotRepository
        extends JpaRepository<TrendKeywordSlot, TrendKeywordSlot.Pk> {

    /**
     * Top5/급상승: term별 현재 윈도우 합(cur) + 직전 윈도우 합(prev) 1행씩 반환.
     * 노이즈컷: cur >= :minCount (cur<2 제외). 정렬: 평활비 (cur+:k)/(prev+:k) 내림차순(분모≥1, div0 없음).
     * 응답 deltaPct(raw)·isNew는 서비스에서 cur/prev로 계산. 카테고리 미지정(null)은 전체.
     * Object[]: [0]=term(String), [1]=cur(Long), [2]=prev(Long)
     */
    @Query(value = """
            SELECT term,
                   COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :curStart), 0) AS cur,
                   COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :prevStart AND slot_start < :curStart), 0) AS prev
            FROM trend_keyword_slot
            WHERE slot_start >= :prevStart
              AND (CAST(:category AS VARCHAR) IS NULL OR category = CAST(:category AS VARCHAR))
            GROUP BY term
            HAVING COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :curStart), 0) >= :minCount
            ORDER BY (COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :curStart), 0) + :k)::numeric
                     / (COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :prevStart AND slot_start < :curStart), 0) + :k) DESC,
                     COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :curStart), 0) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRisingKeywords(
            @Param("curStart") Instant curStart,
            @Param("prevStart") Instant prevStart,
            @Param("category") String category,
            @Param("minCount") int minCount,
            @Param("k") int k,
            @Param("limit") int limit);

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
            WHERE a.first_collected_at >= :windowStart AND a.admin_hidden_at IS NULL
            GROUP BY 1, 2, 3
            ON CONFLICT (slot_start, category, term)
                DO UPDATE SET article_count = EXCLUDED.article_count, updated_at = NOW()
            """, nativeQuery = true)
    int upsertSlots(@Param("windowStart") Instant windowStart);

    /** 보존 정리: 90일 경과 슬롯 삭제. */
    @Modifying
    @Query(value = "DELETE FROM trend_keyword_slot WHERE slot_start < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * 워드클라우드: 윈도우 내 term별 weight = SUM(article_count). term-scoped라 과대계상 없음.
     * min-freq 컷(weight >= :minCount). weight 내림차순.
     * Object[]: [0]=term(String), [1]=weight(Long)
     */
    @Query(value = """
            SELECT term, SUM(article_count) AS weight
            FROM trend_keyword_slot
            WHERE slot_start >= :windowStart
            GROUP BY term
            HAVING SUM(article_count) >= :minCount
            ORDER BY weight DESC, term ASC
            """, nativeQuery = true)
    List<Object[]> wordcloud(
            @Param("windowStart") Instant windowStart, @Param("minCount") int minCount);

    /**
     * 이슈 delta 산출용: term별 cur주/prev주 합. (노이즈컷·정렬 없음 — 전 term 대상)
     * Object[]: [0]=term(String), [1]=cur(Long), [2]=prev(Long)
     */
    @Query(value = """
            SELECT term,
                   COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :curStart), 0) AS cur,
                   COALESCE(SUM(article_count) FILTER (WHERE slot_start >= :prevStart AND slot_start < :curStart), 0) AS prev
            FROM trend_keyword_slot
            WHERE slot_start >= :prevStart
            GROUP BY term
            """, nativeQuery = true)
    List<Object[]> weeklyKeywordCounts(
            @Param("curStart") Instant curStart, @Param("prevStart") Instant prevStart);
}
