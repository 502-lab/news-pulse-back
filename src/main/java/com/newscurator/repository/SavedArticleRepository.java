package com.newscurator.repository;

import com.newscurator.domain.SavedArticle;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedArticleRepository extends JpaRepository<SavedArticle, Long> {

    List<SavedArticle> findByAccountIdOrderBySavedAtDesc(UUID accountId, Pageable pageable);

    // cursor 기반 savedAt DESC 페이지네이션
    @Query("SELECT sa FROM SavedArticle sa WHERE sa.accountId = :accountId "
            + "AND (sa.savedAt < :cursorSavedAt "
            + "     OR (sa.savedAt = :cursorSavedAt AND sa.id < :cursorId)) "
            + "ORDER BY sa.savedAt DESC, sa.id DESC")
    List<SavedArticle> findByAccountIdWithCursor(
            @Param("accountId") UUID accountId,
            @Param("cursorSavedAt") Instant cursorSavedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    boolean existsByAccountIdAndArticleId(UUID accountId, Long articleId);

    long countByAccountId(UUID accountId);

    void deleteByAccountIdAndArticleId(UUID accountId, Long articleId);

    @Query("SELECT sa.articleId FROM SavedArticle sa "
            + "WHERE sa.accountId = :accountId AND sa.articleId IN :articleIds")
    Set<Long> findSavedArticleIdsByAccountIdAndArticleIdIn(
            @Param("accountId") UUID accountId,
            @Param("articleIds") Collection<Long> articleIds);
}
