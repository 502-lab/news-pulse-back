package com.newscurator.repository;

import com.newscurator.domain.SourceDailyUsage;
import com.newscurator.domain.SourceDailyUsageId;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * 008 US2 — 소스별 수집량 집계(기간). Object[]: [0]=source_id(Long), [1]=totalCalls(Long).
     * 빈 데이터 시 빈 목록(0/빈값 안전).
     */
    @Query(
            value =
                    "SELECT source_id, COALESCE(SUM(call_count), 0) FROM source_daily_usage"
                            + " WHERE usage_date >= :since GROUP BY source_id ORDER BY 2 DESC",
            nativeQuery = true)
    List<Object[]> volumeSince(@Param("since") LocalDate since);

    /**
     * 008 US5 수집량 드릴다운: 특정 소스의 일자별 call_count. Object[]: [0]=usage_date(Date), [1]=call_count(Integer).
     * 빈 데이터면 빈 목록.
     */
    @Query(
            value =
                    "SELECT usage_date, call_count FROM source_daily_usage"
                            + " WHERE source_id = :sourceId AND usage_date >= :since"
                            + " ORDER BY usage_date DESC",
            nativeQuery = true)
    List<Object[]> detailBySource(
            @Param("sourceId") Long sourceId, @Param("since") LocalDate since);
}
