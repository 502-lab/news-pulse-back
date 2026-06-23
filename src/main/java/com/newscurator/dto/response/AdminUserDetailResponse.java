package com.newscurator.dto.response;

import com.newscurator.domain.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 어드민 사용자 상세 + UserStats(008 US1). 기존 accounts 데이터로 산출(조인 없음).
 */
@Schema(description = "어드민 사용자 상세 + 통계")
public record AdminUserDetailResponse(
        @Schema(description = "계정 식별자") UUID id,
        @Schema(description = "이메일") String email,
        @Schema(description = "역할", example = "USER") String role,
        @Schema(description = "상태", example = "ACTIVE") String status,
        @Schema(description = "가입 유형", example = "EMAIL") String signupType,
        @Schema(description = "이메일 인증 여부") boolean emailVerified,
        @Schema(description = "온보딩 완료 여부") boolean onboardingCompleted,
        @Schema(description = "가입 시각") Instant createdAt) {

    public static AdminUserDetailResponse from(Account a) {
        return new AdminUserDetailResponse(
                a.getId(),
                a.getEmail(),
                a.getRole().name(),
                a.getStatus().name(),
                a.getSignupType() == null ? null : a.getSignupType().name(),
                a.isEmailVerified(),
                a.isOnboardingCompleted(),
                a.getCreatedAt());
    }
}
