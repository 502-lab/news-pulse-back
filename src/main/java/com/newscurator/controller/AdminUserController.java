package com.newscurator.controller;

import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.RoleChangeRequest;
import com.newscurator.dto.request.StatusChangeRequest;
import com.newscurator.dto.response.AdminUserDetailResponse;
import com.newscurator.dto.response.AdminUserSummaryResponse;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 사용자 관리 API(008 US1). 전 경로 {@code /api/v1/admin/**} → hasRole(ADMIN)(기존 SecurityConfig).
 */
@Tag(name = "Admin Users", description = "어드민 사용자 관리 API (ADMIN 전용)")
@RestController
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Operation(
            summary = "사용자 목록",
            description = "이메일 부분검색·역할·상태·가입유형 옵션 필터 + 페이지네이션. ADMIN 전용.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 아님")
    })
    @GetMapping("/api/v1/admin/users")
    public ResponseEntity<ApiResponse<Page<AdminUserSummaryResponse>>> list(
            @Parameter(description = "이메일 부분검색") @RequestParam(required = false) String email,
            @Parameter(description = "역할 필터") @RequestParam(required = false) AccountRole role,
            @Parameter(description = "상태 필터") @RequestParam(required = false) AccountStatus status,
            @Parameter(description = "가입유형 필터") @RequestParam(required = false) SignupType signupType,
            Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(adminUserService.list(email, role, status, signupType, pageable)));
    }

    @Operation(summary = "사용자 상세 + 통계", description = "단일 사용자 상세(UserStats 포함). ADMIN 전용.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @GetMapping("/api/v1/admin/users/{accountId}")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> detail(
            @Parameter(description = "계정 식별자") @PathVariable UUID accountId) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getDetail(accountId)));
    }

    @Operation(
            summary = "역할 변경(USER↔ADMIN)",
            description = "자기 자신 강등 금지(409), 마지막 ADMIN 강등 금지(409). ADMIN 전용.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "자기/마지막 ADMIN 가드"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @PatchMapping("/api/v1/admin/users/{accountId}/role")
    public ResponseEntity<ApiResponse<Void>> changeRole(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Parameter(description = "대상 계정") @PathVariable UUID accountId,
            @Valid @RequestBody RoleChangeRequest request) {
        adminUserService.changeRole(actor.getAccountId(), accountId, request.role());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(
            summary = "활성/비활성 변경",
            description = "비활성(SUSPENDED) 시 로그인·토큰 차단. 자기 자신·마지막 ADMIN 비활성 금지(409). ADMIN 전용.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "자기/마지막 ADMIN 가드"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @PatchMapping("/api/v1/admin/users/{accountId}/status")
    public ResponseEntity<ApiResponse<Void>> changeStatus(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Parameter(description = "대상 계정") @PathVariable UUID accountId,
            @Valid @RequestBody StatusChangeRequest request) {
        adminUserService.changeStatus(actor.getAccountId(), accountId, request.active());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
