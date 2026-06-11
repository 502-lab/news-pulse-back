package com.newscurator.repository;

import com.newscurator.domain.ReadingPreference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingPreferenceRepository extends JpaRepository<ReadingPreference, UUID> {
    Optional<ReadingPreference> findByAccountId(UUID accountId);
}
