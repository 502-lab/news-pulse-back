package com.newscurator.repository;

import com.newscurator.domain.NotificationPreferences;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {
}
