package com.newscurator.repository;

import com.newscurator.domain.ArticleSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleSourceRepository extends JpaRepository<ArticleSource, Long> {

    boolean existsByArticleIdAndSourceId(Long articleId, Long sourceId);

    // 통계: 오늘 병합 건수
    @Query(
            "SELECT COUNT(a) FROM ArticleSource a "
                    + "WHERE a.merge = true "
                    + "AND CAST(a.collectedAt AS LocalDate) = :today")
    long countMergeToday(@Param("today") LocalDate today);

    // 통계: 오늘 전체 수집 건수 (중복 포함)
    @Query(
            "SELECT COUNT(a) FROM ArticleSource a "
                    + "WHERE a.collectedAt >= :startOfDay AND a.collectedAt < :endOfDay")
    long countCollectedBetween(
            @Param("startOfDay") OffsetDateTime startOfDay,
            @Param("endOfDay") OffsetDateTime endOfDay);

    // spec 003 피드·검색: 기사 목록의 source_name 일괄 조회 (N+1 방지)
    @Query("SELECT a FROM ArticleSource a JOIN FETCH a.source "
            + "WHERE a.article.id IN :articleIds ORDER BY a.id ASC")
    java.util.List<ArticleSource> findWithSourceByArticleIdIn(
            @Param("articleIds") java.util.Collection<Long> articleIds);
}
