package com.newscurator.repository;

import com.newscurator.domain.Article;
import com.newscurator.domain.enums.ProcessingStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findByNormalizedUrl(String normalizedUrl);

    boolean existsByNormalizedUrl(String normalizedUrl);

    // 007 트렌드 추출 대상 게이팅: article_keyword 미보유 + summary-race 게이트
    //   COMPLETED / FAILED → 즉시 추출, PENDING은 수집 후 1h 경과해야(요약 대기) 제목만 추출,
    //   1h 이내 PENDING은 제외(이번 run skip → 다음 run 대기). NOT EXISTS로 재추출 안 함.
    @Query(value = """
            SELECT * FROM articles a
            WHERE a.first_collected_at >= :windowStart
              AND NOT EXISTS (SELECT 1 FROM article_keyword ak WHERE ak.article_id = a.id)
              AND ( a.summary_status IN ('COMPLETED','FAILED')
                    OR (a.summary_status = 'PENDING' AND a.first_collected_at < :summaryCutoff) )
            ORDER BY a.first_collected_at ASC
            """, nativeQuery = true)
    List<Article> findTrendExtractionCandidates(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("summaryCutoff") OffsetDateTime summaryCutoff);

    // SELECT ... FOR UPDATE SKIP LOCKED: 다중 인스턴스 동시 실행 safe (research #13)
    // category_status = PENDING 기사를 batch-size 건 잠금 획득 후 반환
    @Query(
            value =
                    "SELECT * FROM articles "
                            + "WHERE category_status = 'PENDING' OR summary_status = 'PENDING' "
                            + "ORDER BY first_collected_at ASC "
                            + "LIMIT :limit "
                            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Article> lockAndClaimPending(@Param("limit") int limit);

    // 피드 조회: 커서 없음 (첫 페이지)
    @Query(
            "SELECT a FROM Article a "
                    + "WHERE a.feedVisible = true "
                    + "AND a.categoryStatus IN :statuses "
                    + "ORDER BY a.publishedAt DESC, a.id DESC")
    List<Article> findFeedPage(
            @Param("statuses") List<ProcessingStatus> statuses,
            org.springframework.data.domain.Pageable pageable);

    // 피드 조회: 커서 기반 (published_at+id 복합 커서)
    @Query(
            "SELECT a FROM Article a "
                    + "WHERE a.feedVisible = true "
                    + "AND a.categoryStatus IN :statuses "
                    + "AND (a.publishedAt < :cursorPublishedAt "
                    + "     OR (a.publishedAt = :cursorPublishedAt AND a.id < :cursorId)) "
                    + "ORDER BY a.publishedAt DESC, a.id DESC")
    List<Article> findFeedPageWithCursor(
            @Param("statuses") List<ProcessingStatus> statuses,
            @Param("cursorPublishedAt") OffsetDateTime cursorPublishedAt,
            @Param("cursorId") Long cursorId,
            org.springframework.data.domain.Pageable pageable);

    // 카테고리 필터 피드 조회: 첫 페이지
    @Query(
            "SELECT a FROM Article a "
                    + "WHERE a.feedVisible = true "
                    + "AND a.categoryStatus IN :statuses "
                    + "AND a.category = :category "
                    + "ORDER BY a.publishedAt DESC, a.id DESC")
    List<Article> findFeedPageByCategory(
            @Param("statuses") List<ProcessingStatus> statuses,
            @Param("category") com.newscurator.domain.enums.Category category,
            org.springframework.data.domain.Pageable pageable);

    // 카테고리 필터 피드 조회: 커서 기반
    // 커서가 만료·삭제된 기사를 가리켜도 커서 위치 이후 결과를 graceful하게 반환
    @Query(
            "SELECT a FROM Article a "
                    + "WHERE a.feedVisible = true "
                    + "AND a.categoryStatus IN :statuses "
                    + "AND a.category = :category "
                    + "AND (a.publishedAt < :cursorPublishedAt "
                    + "     OR (a.publishedAt = :cursorPublishedAt AND a.id < :cursorId)) "
                    + "ORDER BY a.publishedAt DESC, a.id DESC")
    List<Article> findFeedPageByCategoryWithCursor(
            @Param("statuses") List<ProcessingStatus> statuses,
            @Param("category") com.newscurator.domain.enums.Category category,
            @Param("cursorPublishedAt") OffsetDateTime cursorPublishedAt,
            @Param("cursorId") Long cursorId,
            org.springframework.data.domain.Pageable pageable);

    // 만료 처리 1단계: feed_visible = false
    @Modifying
    @Query(
            "UPDATE Article a SET a.feedVisible = false "
                    + "WHERE a.feedVisible = true AND a.userSaved = false AND a.expiresAt < :now")
    int hideExpiredArticles(@Param("now") OffsetDateTime now);

    // 만료 처리 2단계: 물리 삭제 대상 조회 (grace period 경과 + user_saved=false)
    @Query(
            "SELECT a FROM Article a "
                    + "WHERE a.feedVisible = false "
                    + "AND a.userSaved = false "
                    + "AND a.updatedAt < :graceCutoff")
    List<Article> findArticlesToDelete(@Param("graceCutoff") OffsetDateTime graceCutoff);

    // 통계: 오늘 수집 건수
    @Query(
            "SELECT COUNT(a) FROM Article a "
                    + "WHERE CAST(a.firstCollectedAt AS LocalDate) = :today")
    long countCollectedToday(@Param("today") java.time.LocalDate today);

    // 통계: summary_status별 카운트
    @Query("SELECT COUNT(a) FROM Article a WHERE a.summaryStatus = :status")
    long countBySummaryStatus(@Param("status") ProcessingStatus status);

    // 통계: category_status별 카운트
    @Query("SELECT COUNT(a) FROM Article a WHERE a.categoryStatus = :status")
    long countByCategoryStatus(@Param("status") ProcessingStatus status);

    // 통계: summary 완료율 분모 (category_status∈{COMPLETED,FAILED})
    @Query(
            "SELECT COUNT(a) FROM Article a "
                    + "WHERE a.categoryStatus IN :statuses")
    long countByCategoryStatusIn(@Param("statuses") List<ProcessingStatus> statuses);

    // 통계: summary 완료 건수 (분자)
    @Query("SELECT COUNT(a) FROM Article a WHERE a.summaryStatus = 'COMPLETED'")
    long countSummaryCompleted();

    // spec 003 개인화 피드: 시간 창 내 후보 기사 전체 조회 (Java에서 랭킹 정렬)
    @Query("SELECT a FROM Article a "
            + "WHERE a.feedVisible = true "
            + "AND a.categoryStatus IN :statuses "
            + "AND a.publishedAt >= :windowStart AND a.publishedAt <= :refTs "
            + "ORDER BY a.id ASC")
    List<Article> findFeedCandidates(
            @Param("statuses") List<ProcessingStatus> statuses,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("refTs") OffsetDateTime refTs,
            org.springframework.data.domain.Pageable pageable);

    // spec 003 개인화 피드 (카테고리 필터)
    @Query("SELECT a FROM Article a "
            + "WHERE a.feedVisible = true "
            + "AND a.categoryStatus IN :statuses "
            + "AND a.category = :category "
            + "AND a.publishedAt >= :windowStart AND a.publishedAt <= :refTs "
            + "ORDER BY a.id ASC")
    List<Article> findFeedCandidatesByCategory(
            @Param("statuses") List<ProcessingStatus> statuses,
            @Param("category") com.newscurator.domain.enums.Category category,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("refTs") OffsetDateTime refTs,
            Pageable pageable);

    // spec 003 검색: pg_bigm GREATEST(bigm_similarity) relevance 정렬 — 첫 페이지
    // 주의: '%' || :q || '%' 패턴 필수 (리터럴 '%:q%' 사용 시 JPA가 :q를 바인딩하지 않고 결과 0건 발생)
    // Object[] 행: [0]=id(Long), [1]=relevance_score(Float), [2]=source_name(String|null), [3]=published_at(Timestamp)
    @Query(value = """
            SELECT a.id,
                   GREATEST(
                       bigm_similarity(a.title, :q),
                       COALESCE((SELECT MAX(bigm_similarity(s.content, :q))
                                 FROM summaries s
                                 WHERE s.article_id = a.id AND s.status = 'COMPLETED'), 0.0)
                   ) AS relevance_score,
                   (SELECT src.name FROM article_sources asrc
                    JOIN sources src ON src.id = asrc.source_id
                    WHERE asrc.article_id = a.id
                    ORDER BY asrc.collected_at ASC
                    LIMIT 1) AS source_name,
                   a.published_at
            FROM articles a
            WHERE a.feed_visible = true
              AND a.category_status IN ('COMPLETED', 'FAILED')
              AND a.published_at >= NOW() - INTERVAL '90 days'
              AND (
                  a.title LIKE '%' || :q || '%'
                  OR EXISTS (SELECT 1 FROM summaries s2
                             WHERE s2.article_id = a.id
                             AND s2.content LIKE '%' || :q || '%')
              )
            ORDER BY relevance_score DESC, a.published_at DESC, a.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchByQuery(@Param("q") String q, @Param("limit") int limit);

    // spec 003 검색 — 커서 기반 (CTE로 relevance_score 한 번만 계산)
    // Object[] 행: [0]=id(Long), [1]=relevance_score(Float), [2]=source_name(String|null), [3]=published_at(Timestamp)
    @Query(value = """
            WITH scored AS (
                SELECT a.id,
                       GREATEST(
                           bigm_similarity(a.title, :q),
                           COALESCE((SELECT MAX(bigm_similarity(s.content, :q))
                                     FROM summaries s
                                     WHERE s.article_id = a.id AND s.status = 'COMPLETED'), 0.0)
                       ) AS relevance_score,
                       (SELECT src.name FROM article_sources asrc
                        JOIN sources src ON src.id = asrc.source_id
                        WHERE asrc.article_id = a.id
                        ORDER BY asrc.collected_at ASC
                        LIMIT 1) AS source_name,
                       a.published_at
                FROM articles a
                WHERE a.feed_visible = true
                  AND a.category_status IN ('COMPLETED', 'FAILED')
                  AND a.published_at >= NOW() - INTERVAL '90 days'
                  AND (
                      a.title LIKE '%' || :q || '%'
                      OR EXISTS (SELECT 1 FROM summaries s2
                                 WHERE s2.article_id = a.id
                                 AND s2.content LIKE '%' || :q || '%')
                  )
            )
            SELECT id, relevance_score, source_name, published_at
            FROM scored
            WHERE (relevance_score < :cursorScore
                   OR (relevance_score = :cursorScore
                       AND (published_at < :cursorPublishedAt
                            OR (published_at = :cursorPublishedAt AND id < :cursorId))))
            ORDER BY relevance_score DESC, published_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchByQueryWithCursor(
            @Param("q") String q,
            @Param("cursorScore") double cursorScore,
            @Param("cursorPublishedAt") OffsetDateTime cursorPublishedAt,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);
}
