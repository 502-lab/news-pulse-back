package com.newscurator.repository;

import com.newscurator.domain.ArticleKeyword;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleKeywordRepository
        extends JpaRepository<ArticleKeyword, ArticleKeyword.Pk> {

    /** 멱등 삽입: 동일 (article_id, term) 재추출은 no-op. */
    @Modifying
    @Query(value = """
            INSERT INTO article_keyword (article_id, term, created_at)
            VALUES (:articleId, :term, NOW())
            ON CONFLICT (article_id, term) DO NOTHING
            """, nativeQuery = true)
    void insertIgnore(@Param("articleId") Long articleId, @Param("term") String term);

    /**
     * 히트맵: (시간버킷 × 카테고리) 격자의 기사 볼륨 = COUNT(DISTINCT article).
     * trend_keyword_slot의 per-term SUM은 기사 과대계상 → 여기서는 article_keyword JOIN articles로
     * DISTINCT 기사만 센다. 윈도우 바운드(first_collected_at >= :windowStart).
     * Object[]: [0]=slot_start(Timestamp), [1]=category(String), [2]=article_count(Long DISTINCT)
     */
    @Query(value = """
            SELECT date_trunc('hour', a.first_collected_at) AS slot_start,
                   COALESCE(a.category, 'OTHER')            AS category,
                   COUNT(DISTINCT a.id)                     AS article_count
            FROM article_keyword ak
                     JOIN articles a ON a.id = ak.article_id
            WHERE a.first_collected_at >= :windowStart
            GROUP BY 1, 2
            ORDER BY 1, 2
            """, nativeQuery = true)
    List<Object[]> heatmap(@Param("windowStart") Instant windowStart);

    /**
     * 이슈 클러스터링 입력: 윈도우 내 (article_id, term) 페어 전체. 서비스가 Map<articleId, List<term>>로 그룹화.
     * Object[]: [0]=article_id(Long), [1]=term(String)
     */
    @Query(value = """
            SELECT ak.article_id, ak.term
            FROM article_keyword ak
                     JOIN articles a ON a.id = ak.article_id
            WHERE a.first_collected_at >= :windowStart
            """, nativeQuery = true)
    List<Object[]> windowArticleKeywords(@Param("windowStart") Instant windowStart);
}
