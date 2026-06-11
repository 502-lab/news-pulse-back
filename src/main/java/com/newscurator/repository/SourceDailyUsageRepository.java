package com.newscurator.repository;

import com.newscurator.domain.SourceDailyUsage;
import com.newscurator.domain.SourceDailyUsageId;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceDailyUsageRepository
        extends JpaRepository<SourceDailyUsage, SourceDailyUsageId> {

    Optional<SourceDailyUsage> findByIdSourceIdAndIdUsageDate(Long sourceId, LocalDate usageDate);

    // @Modifying + RETURNING은 Hibernate 6에서 executeUpdate()를 강제해 결과를 받지 못함.
    // upsert → select 분리로 처리 (동일 트랜잭션 내이므로 정합성 보장)
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "INSERT INTO source_daily_usage (source_id, usage_date, call_count) "
                            + "VALUES (:sourceId, :usageDate, 1) "
                            + "ON CONFLICT (source_id, usage_date) "
                            + "DO UPDATE SET call_count = source_daily_usage.call_count + 1",
            nativeQuery = true)
    void upsertCallCount(
            @Param("sourceId") Long sourceId, @Param("usageDate") LocalDate usageDate);

    @Query(
            value =
                    "SELECT call_count FROM source_daily_usage "
                            + "WHERE source_id = :sourceId AND usage_date = :usageDate",
            nativeQuery = true)
    int selectCallCount(
            @Param("sourceId") Long sourceId, @Param("usageDate") LocalDate usageDate);

    @Query(
            "SELECT COALESCE(u.callCount, 0) FROM SourceDailyUsage u "
                    + "WHERE u.id.sourceId = :sourceId AND u.id.usageDate = :usageDate")
    Optional<Integer> findCallCount(
            @Param("sourceId") Long sourceId, @Param("usageDate") LocalDate usageDate);
}
