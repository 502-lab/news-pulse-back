package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Schema(description = "소셜 OAuth 콜백 요청")
public record SocialCallbackRequest(
        @Schema(description = "OAuth 인가 코드 (provider redirect에서 추출)")
        @NotBlank String code,

        @Schema(description = "HMAC 서명 state JWT (CSRF 방지 — /authorize 응답의 authorizeUrl에 포함된 값)")
        @NotBlank String state,

        @Schema(description = "프론트엔드 콜백 URI — /authorize 요청 시 전달한 값과 동일해야 함 (provider 토큰 교환에 사용)")
        @NotBlank String redirectUri,

        @Schema(description = "Apple 최초 로그인 시 provider가 form_post로 전달하는 userInfo JSON (선택)", nullable = true)
        String userJson,

        @Schema(description = "신규 소셜 가입 시 약관 동의 목록. 기존 계정 로그인 시 무시.", nullable = true)
        List<ConsentInput> consents,

        @Schema(description = "신규 소셜 가입 시 만 14세 이상 동의. 기존 계정 로그인 시 무시.", nullable = true)
        Boolean ageConfirmed
) {}
