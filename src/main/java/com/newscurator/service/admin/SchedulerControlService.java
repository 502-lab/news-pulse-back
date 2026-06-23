package com.newscurator.service.admin;

import com.newscurator.domain.SchedulerSetting;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.repository.SchedulerSettingRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스케줄러 런타임 토글 제어(008 FR-031). 각 {@code @Scheduled} 메서드가 진입 시 {@link #isEnabled(String)}을
 * 조회해 false면 skip한다. DB 영속이라 재기동 후에도 상태 유지(SC-010).
 *
 * <p>설정 행이 없으면 기본 enabled=true(기존 전역 토글 {@code matchIfMissing=true} 의미 유지).
 */
@Service
public class SchedulerControlService {

    private static final String ACTION_TOGGLE = "SCHEDULER_TOGGLE";

    private final SchedulerSettingRepository settingRepository;
    private final AdminAuditService auditService;

    public SchedulerControlService(
            SchedulerSettingRepository settingRepository, AdminAuditService auditService) {
        this.settingRepository = settingRepository;
        this.auditService = auditService;
    }

    /** 스케줄러 활성 여부. 행 부재 시 기본 true. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String schedulerKey) {
        return settingRepository
                .findBySchedulerKey(schedulerKey)
                .map(SchedulerSetting::isEnabled)
                .orElse(true);
    }

    /** 전체 스케줄러 설정(모니터링·관리 조회용). */
    @Transactional(readOnly = true)
    public List<SchedulerSetting> findAll() {
        return settingRepository.findAll();
    }

    /**
     * 토글 영속(+ 감사). 행 부재 시 생성. 같은 TX 내 감사 기록.
     */
    @Transactional
    public void setEnabled(String schedulerKey, boolean enabled, UUID actorAccountId) {
        SchedulerSetting setting =
                settingRepository
                        .findBySchedulerKey(schedulerKey)
                        .orElseGet(
                                () ->
                                        SchedulerSetting.builder()
                                                .schedulerKey(schedulerKey)
                                                .enabled(enabled)
                                                .updatedBy(actorAccountId)
                                                .build());
        setting.updateEnabled(enabled, actorAccountId);
        settingRepository.save(setting);
        auditService.record(
                actorAccountId,
                ACTION_TOGGLE,
                AuditTargetType.SCHEDULER,
                schedulerKey,
                Map.of("enabled", enabled));
    }
}
