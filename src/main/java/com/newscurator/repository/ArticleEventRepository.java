package com.newscurator.repository;

import com.newscurator.domain.ArticleEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 009 읽기 추적 — 조회 이벤트 적재/질의.
 *
 * <p>디바운스 조건부 INSERT·읽은수(distinct)·이력(article 최신 1건)은 native 쿼리. nullable 커서는
 * Postgres 타입 추론 실패 회피를 위해 {@code CAST(:p AS timestamptz)} 처리.
 */
public interface ArticleEventRepository extends JpaRepository<ArticleEvent, Long> {

    /**
     * 디바운스 조건부 INSERT(VIEW·SERVER). 같은 (account, article)의 VIEW가 30분 내 존재하면 INSERT skip.
     *
     * @return 영향 행수(1=기록, 0=디바운스 skip)
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at) "
                        + "SELECT CAST(:accountId AS uuid), :articleId, 'VIEW', 'SERVER', NOW() "
                        + "WHERE NOT EXISTS ("
                        + "  SELECT 1 FROM article_event"
                        + "  WHERE account_id = CAST(:accountId AS uuid) AND article_id = :articleId"
                        + "    AND event_type = 'VIEW' AND occurred_at > NOW() - INTERVAL '30 minutes')",
            nativeQuery = true)
    int insertViewDebounced(
            @Param("accountId") UUID accountId, @Param("articleId") Long articleId);

    /** 읽은수 = 고유 기사 수(distinct article, VIEW 기준). */
    @Query(
            value =
                    "SELECT COUNT(DISTINCT article_id) FROM article_event"
                        + " WHERE account_id = CAST(:accountId AS uuid) AND event_type = 'VIEW'",
            nativeQuery = true)
    long countDistinctArticlesByAccount(@Param("accountId") UUID accountId);

    /**
     * 조회 이력 — article 기준 최신 1건(F3, 같은 기사 다회 조회는 1건). 커서(lastViewedAt) 미만, 최신순.
     * 기사 메타(title) 조인으로 N+1 회피.
     */
    @Query(
            value =
                    "SELECT a.id AS articleId, t.last_viewed_at AS lastViewedAt, a.title AS title FROM ("
                        + "  SELECT article_id, MAX(occurred_at) AS last_viewed_at FROM article_event"
                        + "  WHERE account_id = CAST(:accountId AS uuid) AND event_type = 'VIEW'"
                        + "  GROUP BY article_id) t "
                        + "JOIN articles a ON a.id = t.article_id "
                        + "WHERE (CAST(:cursor AS timestamptz) IS NULL"
                        + "       OR t.last_viewed_at < CAST(:cursor AS timestamptz)) "
                        + "ORDER BY t.last_viewed_at DESC "
                        + "LIMIT :size",
            nativeQuery = true)
    List<ArticleViewHistoryRow> findHistory(
            @Param("accountId") UUID accountId,
            @Param("cursor") Instant cursor,
            @Param("size") int size);

    /** 이력 행 projection(native alias = property명, 대소문자 무시 매칭). */
    interface ArticleViewHistoryRow {
        Long getArticleId();

        Instant getLastViewedAt();

        String getTitle();
    }
}
