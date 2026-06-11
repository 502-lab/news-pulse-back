package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "소셜 OAuth 인가 URL 응답")
public record SocialAuthorizeResponse(
        @Schema(description = "provider의 인가 페이지 URL (state JWT 포함)")
        String authorizeUrl
) {}
