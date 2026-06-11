package com.newscurator.repository;

import com.newscurator.domain.VerificationCode;
import com.newscurator.domain.enums.VerificationPurpose;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findTopByAccountIdAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
            UUID accountId, VerificationPurpose purpose);

    List<VerificationCode> findByAccountIdAndPurposeAndIsUsedFalse(
            UUID accountId, VerificationPurpose purpose);

    @Modifying
    @Query("UPDATE VerificationCode v SET v.isUsed = TRUE " +
           "WHERE v.account.id = :accountId AND v.purpose = :purpose AND v.isUsed = FALSE")
    int invalidateAllActive(@Param("accountId") UUID accountId,
                            @Param("purpose") VerificationPurpose purpose);

    @Query("SELECT COALESCE(MAX(v.hourlyCount), 0) FROM VerificationCode v " +
           "WHERE v.account.id = :accountId AND v.purpose = :purpose " +
           "AND v.windowStart > :since")
    int maxHourlyCountSince(@Param("accountId") UUID accountId,
                            @Param("purpose") VerificationPurpose purpose,
                            @Param("since") Instant since);
}
