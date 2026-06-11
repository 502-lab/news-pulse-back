package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "비밀번호 재설정 확인 요청")
public record PasswordResetConfirmRequest(
        @Schema(description = "verify 단계에서 발급된 단일 사용 resetToken JWT")
        @NotBlank String resetToken,

        @Schema(description = "새 비밀번호 (영문+숫자 포함 8자 이상)", example = "NewPass123")
        @NotBlank String newPassword
) {}
