package com.newscurator.dto.response;

import com.newscurator.domain.AdminAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 감사 로그 항목(008 FR-060). 조회 전용. */
@Schema(description = "어드민 감사 로그 항목")
public record AuditLogItemResponse(
        @Schema(description = "식별자") long id,
        @Schema(description = "행위자(관리자) 계정") UUID actorAccountId,
        @Schema(description = "행위") String action,
        @Schema(description = "대상 유형") String targetType,
        @Schema(description = "대상 식별자") String targetId,
        @Schema(description = "변경 내용(JSON)") String detail,
        @Schema(description = "시각") Instant createdAt) {

    public static AuditLogItemResponse from(AdminAuditLog a) {
        return new AuditLogItemResponse(
                a.getId(), a.getActorAccountId(), a.getAction(),
                a.getTargetType() == null ? null : a.getTargetType().name(),
                a.getTargetId(), a.getDetail(), a.getCreatedAt());
    }
}
