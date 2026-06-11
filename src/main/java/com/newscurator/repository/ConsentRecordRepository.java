package com.newscurator.repository;

import com.newscurator.domain.ConsentRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {
    List<ConsentRecord> findByAccountId(UUID accountId);
    Optional<ConsentRecord> findByAccountIdAndTermsVersionId(UUID accountId, UUID termsVersionId);
}
