package com.newscurator.dto.response;

import com.newscurator.domain.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 어드민 사용자 목록 항목(008 US1). accounts 엔티티 비노출 — 응답 전용.
 */
@Schema(description = "어드민 사용자 목록 항목")
public record AdminUserSummaryResponse(
        @Schema(description = "계정 식별자") UUID id,
        @Schema(description = "이메일") String email,
        @Schema(description = "역할", example = "USER") String role,
        @Schema(description = "상태", example = "ACTIVE") String status,
        @Schema(description = "가입 유형", example = "EMAIL") String signupType,
        @Schema(description = "가입 시각") Instant createdAt) {

    public static AdminUserSummaryResponse from(Account a) {
        return new AdminUserSummaryResponse(
                a.getId(),
                a.getEmail(),
                a.getRole().name(),
                a.getStatus().name(),
                a.getSignupType() == null ? null : a.getSignupType().name(),
                a.getCreatedAt());
    }
}
