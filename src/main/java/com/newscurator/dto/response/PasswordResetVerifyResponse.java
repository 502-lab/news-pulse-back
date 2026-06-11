package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 재설정 코드 검증 응답")
public record PasswordResetVerifyResponse(
        @Schema(description = "단일 사용 비밀번호 재설정 토큰 (10분 TTL, 재사용 불가)")
        String resetToken
) {}
