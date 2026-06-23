package com.newscurator.controller;

import com.newscurator.dto.request.ExcludedKeywordRequest;
import com.newscurator.dto.request.SchedulerToggleRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ExcludedKeywordResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.admin.AdminOpsService;
import com.newscurator.service.admin.SchedulerControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 수집·콘텐츠 운영 제어 API(008 US3). 전 경로 {@code /api/v1/admin/**} → hasRole(ADMIN).
 * 스케줄러 토글은 enabled만 노출(주기 동적 변경은 MVP 미수용 — analyze U1).
 */
@Tag(name = "Admin Ops", description = "어드민 수집·콘텐츠 운영 제어 API (ADMIN 전용)")
@RestController
public class AdminOpsController {

    private final AdminOpsService adminOpsService;
    private final SchedulerControlService schedulerControlService;

    public AdminOpsController(
            AdminOpsService adminOpsService, SchedulerControlService schedulerControlService) {
        this.adminOpsService = adminOpsService;
        this.schedulerControlService = schedulerControlService;
    }

    // ── 스케줄러 토글 ──

    @Operation(summary = "스케줄러 enabled 토글", description = "DB 영속(재기동 후 유지). 주기 동적 변경은 MVP 미노출.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PatchMapping("/api/v1/admin/schedulers/{schedulerKey}")
    public ResponseEntity<ApiResponse<Void>> toggleScheduler(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Parameter(description = "스케줄러 키") @PathVariable String schedulerKey,
            @Valid @RequestBody SchedulerToggleRequest request) {
        schedulerControlService.setEnabled(schedulerKey, request.enabled(), actor.getAccountId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 기사 숨김/해제 ──

    @Operation(summary = "기사 숨김", description = "admin_hidden_at 설정(가역). 사용자향 노출 차단.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 없음")
    })
    @PostMapping("/api/v1/admin/articles/{articleId}/hide")
    public ResponseEntity<ApiResponse<Void>> hide(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable Long articleId) {
        adminOpsService.hideArticle(actor.getAccountId(), articleId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "기사 숨김 해제", description = "admin_hidden_at=null(unhide).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 없음")
    })
    @DeleteMapping("/api/v1/admin/articles/{articleId}/hide")
    public ResponseEntity<ApiResponse<Void>> unhide(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable Long articleId) {
        adminOpsService.unhideArticle(actor.getAccountId(), articleId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "요약 재시도 트리거", description = "FAILED 요약을 PENDING으로 되돌려 재처리 큐에 올림.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 없음")
    })
    @PostMapping("/api/v1/admin/articles/{articleId}/summary/retry")
    public ResponseEntity<ApiResponse<Void>> retrySummary(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable Long articleId) {
        adminOpsService.retrySummary(actor.getAccountId(), articleId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 제외 키워드 CRUD ──

    @Operation(summary = "제외 키워드 목록", description = "수집/추출에서 배제되는 키워드.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/excluded-keywords")
    public ResponseEntity<ApiResponse<List<ExcludedKeywordResponse>>> listKeywords() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        adminOpsService.listExcludedKeywords().stream()
                                .map(ExcludedKeywordResponse::from)
                                .toList()));
    }

    @Operation(summary = "제외 키워드 등록", description = "중복 시 422.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "중복/빈값")
    })
    @PostMapping("/api/v1/admin/excluded-keywords")
    public ResponseEntity<ApiResponse<ExcludedKeywordResponse>> addKeyword(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Valid @RequestBody ExcludedKeywordRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ExcludedKeywordResponse.from(
                                adminOpsService.addExcludedKeyword(actor.getAccountId(), request.keyword()))));
    }

    @Operation(summary = "제외 키워드 삭제")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "키워드 없음")
    })
    @DeleteMapping("/api/v1/admin/excluded-keywords/{id}")
    public ResponseEntity<ApiResponse<Void>> removeKeyword(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable Long id) {
        adminOpsService.removeExcludedKeyword(actor.getAccountId(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
