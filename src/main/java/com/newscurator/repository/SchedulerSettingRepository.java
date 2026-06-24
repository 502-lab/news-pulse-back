package com.newscurator.repository;

import com.newscurator.domain.SchedulerSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스케줄러 설정 리포지토리(008 FR-031). scheduler_key 단위 토글 영속.
 */
public interface SchedulerSettingRepository extends JpaRepository<SchedulerSetting, String> {

    Optional<SchedulerSetting> findBySchedulerKey(String schedulerKey);
}
