package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "daily_briefs",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_daily_brief_user_date",
                        columnNames = {"account_id", "brief_date"}))
@Getter
@NoArgsConstructor
public class DailyBrief {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "brief_date", nullable = false)
    private LocalDate briefDate;

    // 재생 큐 (순서 보존). tts_audio_id 없음 — 각 기사 tts_audios로 구성(Model B)
    @Column(name = "article_ids", columnDefinition = "BIGINT[]", nullable = false)
    private Long[] articleIds;

    @Column(name = "voice_id", nullable = false, length = 50)
    private String voiceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public DailyBrief(Account account, LocalDate briefDate, Long[] articleIds, String voiceId) {
        this.account = account;
        this.briefDate = briefDate;
        this.articleIds = articleIds;
        this.voiceId = voiceId;
        this.createdAt = Instant.now();
    }
}
