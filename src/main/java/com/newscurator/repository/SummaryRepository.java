package com.newscurator.repository;

import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.SummaryDepth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByArticleIdAndDepth(Long articleId, SummaryDepth depth);

    List<Summary> findByArticleId(Long articleId);

    @Query(
            "SELECT COUNT(s) FROM Summary s "
                    + "WHERE s.article.id = :articleId")
    long countByArticleId(@Param("articleId") Long articleId);
}
