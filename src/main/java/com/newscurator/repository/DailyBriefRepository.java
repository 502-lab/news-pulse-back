package com.newscurator.repository;

import com.newscurator.domain.DailyBrief;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyBriefRepository extends JpaRepository<DailyBrief, UUID> {

    Optional<DailyBrief> findByAccountIdAndBriefDate(UUID accountId, LocalDate briefDate);
}
