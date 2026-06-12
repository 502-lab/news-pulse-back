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

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findByNormalizedUrl(String normalizedUrl);

    boolean existsByNormalizedUrl(String normalizedUrl);

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
            org.springframework.data.domain.Pageable pageable);
}
