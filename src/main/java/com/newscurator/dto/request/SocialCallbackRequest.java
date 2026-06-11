package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "소셜 OAuth 콜백 요청 (프론트엔드가 provider redirect에서 code/state 추출 후 전송)")
public record SocialCallbackRequest(
        @Schema(description = "OAuth 인가 코드")
        @NotBlank String code,

        @Schema(description = "HMAC 서명 state JWT (CSRF 방지)")
        @NotBlank String state,

        @Schema(description = "Apple 최초 로그인 시 userInfo JSON (선택 사항)", nullable = true)
        String userJson
) {}
