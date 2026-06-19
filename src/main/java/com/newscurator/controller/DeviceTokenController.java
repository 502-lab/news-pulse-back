package com.newscurator.controller;

import com.newscurator.dto.request.DeviceTokenRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.DeviceTokenResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Device Tokens", description = "FCM 디바이스 토큰 등록·삭제")
@RestController
@RequestMapping("/api/v1/me/device-tokens")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @Operation(
            summary = "디바이스 토큰 등록",
            description = "FCM 디바이스 토큰을 등록한다. "
                    + "동일 token이 이미 존재하면 account_id·platform·updated_at을 갱신(upsert). "
                    + "계정당 최대 5개 — 초과 시 가장 오래된 토큰이 자동 삭제된다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료 (upsert)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "요청 본문 유효성 오류")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DeviceTokenRequest request) {

        DeviceTokenResponse response = deviceTokenService.register(
                userDetails.getAccountId(), request.token(), request.platform());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    @Operation(
            summary = "디바이스 토큰 삭제",
            description = "등록된 FCM 디바이스 토큰을 삭제한다. "
                    + "본인 토큰만 삭제 가능 — 타인 토큰 접근 시 404 반환.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "토큰 없음 또는 소유권 없음")
    })
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "삭제할 토큰 ID", required = true)
            @PathVariable Long tokenId) {

        deviceTokenService.delete(userDetails.getAccountId(), tokenId);
        return ResponseEntity.noContent().build();
    }
}
