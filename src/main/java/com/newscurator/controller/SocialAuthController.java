package com.newscurator.controller;

import com.newscurator.domain.enums.SocialProvider;
import com.newscurator.dto.request.SocialCallbackRequest;
import com.newscurator.dto.request.SocialCompleteRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.SocialAuthorizeResponse;
import com.newscurator.exception.InvalidProviderException;
import com.newscurator.service.SocialAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
               description = "provider별 인가 URL 반환. HMAC 서명 state JWT 포함 (CSRF 방지, FR-027). "
                           + "redirectUri는 서버 화이트리스트 검증 후 인가 URL에 포함됨.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인가 URL 반환"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "알 수 없는 provider 또는 허용되지 않은 redirectUri")
    })
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<ApiResponse<SocialAuthorizeResponse>> authorize(
            @Parameter(description = "OAuth provider (kakao / google / apple)")
            @PathVariable String provider,
            @Parameter(description = "프론트엔드 콜백 URI (서버 화이트리스트에 등록된 값만 허용)", required = true)
            @RequestParam @NotBlank String redirectUri) {
        SocialProvider socialProvider = parseProvider(provider);
        return ResponseEntity.ok(ApiResponse.success(socialAuthService.authorize(socialProvider, redirectUri)));
    }

    @Operation(summary = "OAuth 콜백 처리",
               description = "provider에서 받은 code와 state를 검증 후 기존/신규 분기. "
                           + "기존 유저: 200 + account/tokens. "
                           + "신규 유저: 202 + pendingToken(10분) + 전체 활성 약관 목록(requiredTerms). "
                           + "code는 여기서 소진됨. 가입 완료는 POST /complete 호출 필요.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 소셜 계정 로그인"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "신규 유저 — pendingToken 발급. POST /complete로 가입 완료 필요"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "state 유효하지 않음 (위조/만료/provider 불일치)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이메일 계정 이미 존재")
    })
    @PostMapping("/{provider}/callback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> callback(
            @Parameter(description = "OAuth provider (kakao / google / apple)")
            @PathVariable String provider,
            @RequestBody @Valid SocialCallbackRequest request) {
        SocialProvider socialProvider = parseProvider(provider);
        Map<String, Object> result = socialAuthService.callback(
                socialProvider,
                request.code(),
                request.state(),
                request.redirectUri(),
                request.userJson());
        boolean isNew = Boolean.TRUE.equals(result.get("isNew"));
        return ResponseEntity.status(isNew ? 202 : 200)
                .body(isNew ? ApiResponse.accepted(result) : ApiResponse.success(result));
    }

    @Operation(summary = "소셜 신규 가입 완료",
               description = "pendingToken과 약관 동의를 검증 후 계정 생성 + JWT 발급. "
                           + "pendingToken TTL 10분. 필수 약관(SERVICE·PRIVACY) 미동의 또는 "
                           + "ageConfirmed=false 시 422.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "계정 생성 + 로그인 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "pendingToken 만료 또는 서명 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이메일 충돌 (동시 가입 경합)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "필수 약관 미동의 또는 연령 미확인")
    })
    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> complete(
            @RequestBody @Valid SocialCompleteRequest request) {
        Map<String, Object> result = socialAuthService.complete(
                request.pendingToken(),
                request.consents(),
                request.ageConfirmed(),
                request.userInfo());
        return ResponseEntity.status(201).body(ApiResponse.created(result));
    }

    private SocialProvider parseProvider(String provider) {
        try {
            return SocialProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidProviderException("Unknown OAuth provider: " + provider);
        }
    }
}
