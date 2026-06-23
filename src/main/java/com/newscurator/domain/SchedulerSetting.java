package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스케줄러별 런타임 토글 설정(008 FR-031). DB 영속 → 앱 재기동 후에도 유지.
 * 각 {@code @Scheduled} 메서드가 진입 시 {@code enabled}를 조회해 false면 skip한다.
 *
 * <p>{@code intervalOverrideMs}는 스키마에만 보유하고 MVP에서 API로 노출하지 않는다
 * (주기 동적 재스케줄은 후속 — 저장만 하고 적용 안 되는 거짓 약속 회피).
 */
@Entity
@Table(name = "scheduler_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchedulerSetting {

    @Id
    @Column(name = "scheduler_key", length = 64)
    private String schedulerKey;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "interval_override_ms")
    private Long intervalOverrideMs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", columnDefinition = "uuid")
    private UUID updatedBy;

    @Builder
    public SchedulerSetting(String schedulerKey, boolean enabled, UUID updatedBy) {
        this.schedulerKey = schedulerKey;
        this.enabled = enabled;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    /** 활성/비활성 토글. */
    public void updateEnabled(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
