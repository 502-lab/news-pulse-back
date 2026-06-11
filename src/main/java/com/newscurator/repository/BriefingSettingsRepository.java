package com.newscurator.repository;

import com.newscurator.domain.BriefingSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingSettingsRepository extends JpaRepository<BriefingSettings, UUID> {
    Optional<BriefingSettings> findByAccountId(UUID accountId);
}
