package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "비밀번호 재설정 요청 (이메일 발송)")
public record PasswordResetRequestDto(
        @Schema(description = "계정 이메일", example = "user@example.com")
        @NotBlank @Email String email
) {}
