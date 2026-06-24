package com.newscurator.repository;

import com.newscurator.domain.Article;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 010 인사이트 온디맨드 집계 + 추천 후보 — 읽기 전용 native 쿼리(신규 테이블 없음).
 *
 * <p>공통 기준: 읽은 기사 = {@code article_event}(VIEW) JOIN {@code articles}(admin_hidden_at IS NULL).
 * 편향은 {@code bias_analysis.status='DONE'}만. nullable UUID는 {@code CAST(:acc AS uuid)}로 바인딩.
 */
public interface InsightAggregationRepository extends Repository<Article, Long> {

    /** 읽은수 = 고유 기사 수(VIEW distinct, 숨김 제외). */
    @Query(
            value =
                    "SELECT COUNT(DISTINCT ae.article_id) FROM article_event ae"
                        + " JOIN articles a ON a.id = ae.article_id AND a.admin_hidden_at IS NULL"
                        + " WHERE ae.account_id = CAST(:acc AS uuid) AND ae.event_type = 'VIEW'",
            nativeQuery = true)
    long countReadArticles(@Param("acc") UUID accountId);

    /** 북마크 수(003). */
    @Query(
            value = "SELECT COUNT(*) FROM saved_articles WHERE account_id = CAST(:acc AS uuid)",
            nativeQuery = true)
    long countBookmarks(@Param("acc") UUID accountId);

    /** 카테고리 분포(최다 우선). [category, count] */
    @Query(
            value =
                    "SELECT a.category AS category, COUNT(DISTINCT a.id) AS cnt FROM articles a"
                        + " WHERE a.admin_hidden_at IS NULL AND a.category IS NOT NULL AND a.id IN ("
                        + "   SELECT ae.article_id FROM article_event ae"
                        + "   WHERE ae.account_id = CAST(:acc AS uuid) AND ae.event_type = 'VIEW')"
                        + " GROUP BY a.category ORDER BY cnt DESC",
            nativeQuery = true)
    List<Object[]> categoryDistribution(@Param("acc") UUID accountId);

    /** 주요 언론사 top-k. [name, count] */
    @Query(
            value =
                    "SELECT src.name AS name, COUNT(DISTINCT a.id) AS cnt FROM articles a"
                        + " JOIN article_sources asrc ON asrc.article_id = a.id"
                        + " JOIN sources src ON src.id = asrc.source_id"
                        + " WHERE a.admin_hidden_at IS NULL AND a.id IN ("
                        + "   SELECT ae.article_id FROM article_event ae"
                        + "   WHERE ae.account_id = CAST(:acc AS uuid) AND ae.event_type = 'VIEW')"
                        + " GROUP BY src.name ORDER BY cnt DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topOutlets(@Param("acc") UUID accountId, @Param("limit") int limit);

    /** 관심 키워드 분포 top-k(007 article_keyword.term). [keyword, count] */
    @Query(
            value =
                    "SELECT ak.term AS keyword, COUNT(*) AS cnt FROM article_keyword ak"
                        + " WHERE ak.article_id IN ("
                        + "   SELECT ae.article_id FROM article_event ae"
                        + "   JOIN articles a ON a.id = ae.article_id AND a.admin_hidden_at IS NULL"
                        + "   WHERE ae.account_id = CAST(:acc AS uuid) AND ae.event_type = 'VIEW')"
                        + " GROUP BY ak.term ORDER BY cnt DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> keywordDistribution(@Param("acc") UUID accountId, @Param("limit") int limit);

    /**
     * 내 편향 분포(006 DONE만, 버킷 진보[-100,-34]/중립[-33,33]/보수[34,100], 006 aggregateSpectrum 미러).
     * [liberalPercent, neutralPercent, conservativePercent, total] — 0건이면 NULLIF로 비율 NULL.
     */
    @Query(
            value =
                    "SELECT 100.0 * COUNT(*) FILTER (WHERE ba.value BETWEEN -100 AND -34)"
                        + "   / NULLIF(COUNT(*), 0) AS liberal_percent,"
                        + " 100.0 * COUNT(*) FILTER (WHERE ba.value BETWEEN -33 AND 33)"
                        + "   / NULLIF(COUNT(*), 0) AS neutral_percent,"
                        + " 100.0 * COUNT(*) FILTER (WHERE ba.value BETWEEN 34 AND 100)"
                        + "   / NULLIF(COUNT(*), 0) AS conservative_percent,"
                        + " COUNT(*) AS total_count"
                        + " FROM bias_analysis ba"
                        + " WHERE ba.status = 'DONE' AND ba.article_id IN ("
                        + "   SELECT ae.article_id FROM article_event ae"
                        + "   JOIN articles a ON a.id = ae.article_id AND a.admin_hidden_at IS NULL"
                        + "   WHERE ae.account_id = CAST(:acc AS uuid) AND ae.event_type = 'VIEW')",
            nativeQuery = true)
    List<Object[]> biasDistribution(@Param("acc") UUID accountId);

    /**
     * 추천 후보 — 최근 :days일 비숨김 가시 기사 − 이미 조회(VIEW) − 저장. 최근순. 스코어링 입력용 후보 풀.
     */
    @Query(
            value =
                    "SELECT * FROM articles a WHERE a.admin_hidden_at IS NULL AND a.feed_visible = true"
                        + " AND a.published_at >= NOW() - make_interval(days => :days)"
                        + " AND NOT EXISTS (SELECT 1 FROM article_event ae"
                        + "   WHERE ae.account_id = CAST(:acc AS uuid) AND ae.article_id = a.id"
                        + "     AND ae.event_type = 'VIEW')"
                        + " AND NOT EXISTS (SELECT 1 FROM saved_articles sa"
                        + "   WHERE sa.account_id = CAST(:acc AS uuid) AND sa.article_id = a.id)"
                        + " ORDER BY a.published_at DESC LIMIT :limit",
            nativeQuery = true)
    List<Article> findRecommendationCandidates(
            @Param("acc") UUID accountId, @Param("days") int days, @Param("limit") int limit);
}
