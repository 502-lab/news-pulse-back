package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 재설정 코드 검증 요청")
public record PasswordResetVerifyRequest(
        @Schema(description = "계정 이메일", example = "user@example.com")
        @NotBlank @Email String email,

        @Schema(description = "이메일로 받은 6자리 숫자 코드", example = "123456")
        @NotBlank @Size(min = 6, max = 6) String code
) {}
