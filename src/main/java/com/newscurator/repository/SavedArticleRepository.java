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

    // listenable=true: READY TTS가 존재하는 기사만 반환 (첫 페이지)
    @Query(value = "SELECT sa.* FROM saved_articles sa "
            + "WHERE sa.account_id = CAST(:accountId AS UUID) "
            + "AND EXISTS (SELECT 1 FROM tts_audios ta "
            + "            WHERE ta.owner_type = 'ARTICLE' "
            + "              AND ta.status = 'READY' "
            + "              AND CAST(ta.ref_id AS BIGINT) = sa.article_id "
            + "              AND (:voiceId IS NULL OR ta.voice_id = :voiceId)) "
            + "ORDER BY sa.saved_at DESC, sa.id DESC",
            nativeQuery = true)
    List<SavedArticle> findListenableOrderBySavedAtDesc(
            @Param("accountId") UUID accountId,
            @Param("voiceId") String voiceId,
            Pageable pageable);

    // listenable=true: READY TTS가 존재하는 기사만 반환 (cursor 이후)
    @Query(value = "SELECT sa.* FROM saved_articles sa "
            + "WHERE sa.account_id = CAST(:accountId AS UUID) "
            + "AND (sa.saved_at < :cursorSavedAt "
            + "     OR (sa.saved_at = :cursorSavedAt AND sa.id < :cursorId)) "
            + "AND EXISTS (SELECT 1 FROM tts_audios ta "
            + "            WHERE ta.owner_type = 'ARTICLE' "
            + "              AND ta.status = 'READY' "
            + "              AND CAST(ta.ref_id AS BIGINT) = sa.article_id "
            + "              AND (:voiceId IS NULL OR ta.voice_id = :voiceId)) "
            + "ORDER BY sa.saved_at DESC, sa.id DESC",
            nativeQuery = true)
    List<SavedArticle> findListenableWithCursor(
            @Param("accountId") UUID accountId,
            @Param("cursorSavedAt") Instant cursorSavedAt,
            @Param("cursorId") Long cursorId,
            @Param("voiceId") String voiceId,
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
