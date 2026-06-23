package com.newscurator.repository;

import com.newscurator.domain.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 어드민 감사 로그 리포지토리(008 FR-060). 시간 역순 조회.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    /** 전체 감사 로그(시간 역순). */
    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
