package com.newscurator.controller;

import com.newscurator.domain.enums.SocialProvider;
import com.newscurator.dto.request.SocialCallbackRequest;
import com.newscurator.dto.response.SocialAuthorizeResponse;
import com.newscurator.service.SocialAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Social Auth", description = "소셜 OAuth 인증 (Kakao / Google / Apple)")
@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialAuthController {

    private final SocialAuthService socialAuthService;

    public SocialAuthController(SocialAuthService socialAuthService) {
        this.socialAuthService = socialAuthService;
    }

    @Operation(summary = "OAuth 인가 URL 조회",
               description = "provider별 인가 URL 반환. HMAC 서명 state JWT 포함 (CSRF 방지, FR-027).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "인가 URL 반환"),
        @ApiResponse(responseCode = "400", description = "알 수 없는 provider")
    })
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<SocialAuthorizeResponse> authorize(
            @Parameter(description = "OAuth provider (kakao / google / apple)")
            @PathVariable String provider) {
        SocialProvider socialProvider = parseProvider(provider);
        return ResponseEntity.ok(socialAuthService.authorize(socialProvider));
    }

    @Operation(summary = "OAuth 콜백 처리",
               description = "provider에서 받은 code와 state를 검증 후 계정 조회/생성. "
                           + "신규 가입: 201 + emailVerified=true (FR-024). 기존 로그인: 200. "
                           + "이메일 충돌: 409 (FR-007). state 위조/만료: 400 (FR-027).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "기존 소셜 계정 로그인"),
        @ApiResponse(responseCode = "201", description = "소셜 신규 가입"),
        @ApiResponse(responseCode = "400", description = "state 유효하지 않음 (위조/만료/provider 불일치)"),
        @ApiResponse(responseCode = "409", description = "동일 이메일 계정 이미 존재")
    })
    @PostMapping("/{provider}/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @Parameter(description = "OAuth provider (kakao / google / apple)")
            @PathVariable String provider,
            @RequestBody @Valid SocialCallbackRequest request) {
        SocialProvider socialProvider = parseProvider(provider);
        Map<String, Object> result = socialAuthService.callback(
                socialProvider, request.code(), request.state(), request.userJson());
        boolean isNew = Boolean.TRUE.equals(result.get("isNew"));
        return ResponseEntity.status(isNew ? 201 : 200).body(result);
    }

    private SocialProvider parseProvider(String provider) {
        try {
            return SocialProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
        }
    }
}
