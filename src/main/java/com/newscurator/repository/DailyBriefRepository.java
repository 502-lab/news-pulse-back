package com.newscurator.repository;

import com.newscurator.domain.DailyBrief;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyBriefRepository extends JpaRepository<DailyBrief, UUID> {

    Optional<DailyBrief> findByAccountIdAndBriefDate(UUID accountId, LocalDate briefDate);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT db.account_id FROM daily_briefs db WHERE db.voice_id = :voiceId AND :articleId = ANY(db.article_ids)",
            nativeQuery = true)
    java.util.List<UUID> findAccountIdsByArticleIdAndVoiceId(
            @org.springframework.data.repository.query.Param("articleId") Long articleId,
            @org.springframework.data.repository.query.Param("voiceId") String voiceId);
}
