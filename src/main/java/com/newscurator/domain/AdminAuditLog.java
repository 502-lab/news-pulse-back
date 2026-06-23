package com.newscurator.domain;

import com.newscurator.domain.enums.AuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 어드민 감사 로그(008 FR-060). 변형(부수효과) 행위만 기록 — 단순 조회는 대상 아님.
 * 호출자의 트랜잭션에 참여해 기록되므로, 행위가 롤백되면 감사도 함께 롤백된다(고아 감사 0).
 */
@Entity
@Table(name = "admin_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_account_id", nullable = false, columnDefinition = "uuid")
    private UUID actorAccountId;

    @Column(nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private AuditTargetType targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    /** 변경 내용(before/after diff·사유) JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public AdminAuditLog(
            UUID actorAccountId,
            String action,
            AuditTargetType targetType,
            String targetId,
            String detail) {
        this.actorAccountId = actorAccountId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }
}
