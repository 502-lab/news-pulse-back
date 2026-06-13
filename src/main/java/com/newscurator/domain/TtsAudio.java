package com.newscurator.domain;

import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "tts_audios",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_tts_owner_voice",
                        columnNames = {"owner_type", "ref_id", "voice_id"}))
@Getter
@NoArgsConstructor
public class TtsAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private TtsOwnerType ownerType;

    @Column(name = "ref_id", nullable = false, length = 100)
    private String refId; // ARTICLE → article_id 문자열

    @Column(name = "voice_id", nullable = false, length = 50)
    private String voiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TtsStatus status = TtsStatus.PENDING;

    @Column(name = "audio_key", columnDefinition = "TEXT")
    private String audioKey; // S3 key (tts/article/{ref_id}/{voice_id}.mp3). READY 후 설정.

    @Column(name = "duration_sec")
    private Integer durationSec; // Naver API 미제공 → nullable 유지

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public TtsAudio(TtsOwnerType ownerType, String refId, String voiceId) {
        this.ownerType = ownerType;
        this.refId = refId;
        this.voiceId = voiceId;
        this.status = TtsStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = TtsStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void complete(String audioKey, Integer durationSec) {
        this.status = TtsStatus.READY;
        this.audioKey = audioKey;
        this.durationSec = durationSec;
        this.updatedAt = Instant.now();
    }

    public void fail(String errorMsg) {
        this.status = TtsStatus.FAILED;
        this.errorMsg = errorMsg;
        this.updatedAt = Instant.now();
    }

    // FAILED 재시도: 새 행 INSERT 금지(UNIQUE 위반) — 기존 행 UPDATE로 PENDING 리셋
    public void resetToPending() {
        this.status = TtsStatus.PENDING;
        this.audioKey = null;
        this.errorMsg = null;
        this.updatedAt = Instant.now();
    }
}
