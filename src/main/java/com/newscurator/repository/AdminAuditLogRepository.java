package com.newscurator.repository;

import com.newscurator.domain.AdminAuditLog;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 어드민 감사 로그 리포지토리(008 FR-060). 시간 역순 조회 + 필터.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    /** 전체 감사 로그(시간 역순). */
    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 008 US5 감사 조회: action·actor·기간 옵션 필터(null이면 무시), 시간 역순.
     * nullable 파라미터 타입 추론 문제 회피 위해 native + CAST(007 false-schema 교훈과 동일 패턴).
     */
    @Query(
            value =
                    "SELECT * FROM admin_audit_log WHERE "
                            + "(CAST(:action AS text) IS NULL OR action = CAST(:action AS text)) "
                            + "AND (CAST(:actorId AS uuid) IS NULL OR actor_account_id = CAST(:actorId AS uuid)) "
                            + "AND (CAST(:fromTs AS timestamptz) IS NULL OR created_at >= CAST(:fromTs AS timestamptz)) "
                            + "AND (CAST(:toTs AS timestamptz) IS NULL OR created_at <= CAST(:toTs AS timestamptz)) "
                            + "ORDER BY created_at DESC",
            countQuery =
                    "SELECT COUNT(*) FROM admin_audit_log WHERE "
                            + "(CAST(:action AS text) IS NULL OR action = CAST(:action AS text)) "
                            + "AND (CAST(:actorId AS uuid) IS NULL OR actor_account_id = CAST(:actorId AS uuid)) "
                            + "AND (CAST(:fromTs AS timestamptz) IS NULL OR created_at >= CAST(:fromTs AS timestamptz)) "
                            + "AND (CAST(:toTs AS timestamptz) IS NULL OR created_at <= CAST(:toTs AS timestamptz))",
            nativeQuery = true)
    Page<AdminAuditLog> search(
            @Param("action") String action,
            @Param("actorId") String actorId,
            @Param("fromTs") Instant from,
            @Param("toTs") Instant to,
            Pageable pageable);
}
