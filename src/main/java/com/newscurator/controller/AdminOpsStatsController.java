package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.AuditLogItemResponse;
import com.newscurator.dto.response.CollectionDetailItemResponse;
import com.newscurator.dto.response.ErrorLogResponse;
import com.newscurator.dto.response.OpsStatItemResponse;
import com.newscurator.service.admin.AdminOpsStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 심층 통계·에러로그·수집량 상세·감사 조회(008 US5). read-only. {@code /api/v1/admin/**} → hasRole(ADMIN).
 */
@Tag(name = "Admin Ops Stats", description = "어드민 심층 통계/감사 조회 API (ADMIN 전용, 읽기 전용)")
@RestController
public class AdminOpsStatsController {

    private final AdminOpsStatsService opsStatsService;

    public AdminOpsStatsController(AdminOpsStatsService opsStatsService) {
        this.opsStatsService = opsStatsService;
    }

    @Operation(summary = "OpsStats 추이", description = "일자별 수집 처리량(최근 days일). 빈 윈도우면 빈 목록.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/ops/stats")
    public ResponseEntity<ApiResponse<List<OpsStatItemResponse>>> opsStats(
            @Parameter(description = "기간(일, 기본 30)") @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(opsStatsService.getOpsStats(days)));
    }

    @Operation(
            summary = "에러 로그(FAILED 집계)",
            description = "기존 FAILED 상태(요약/편향/알림) 집계. 신규 테이블 없음. 빈 DB면 0.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/ops/errors")
    public ResponseEntity<ApiResponse<ErrorLogResponse>> errors() {
        return ResponseEntity.ok(ApiResponse.success(opsStatsService.getErrorLog()));
    }

    @Operation(summary = "소스 수집량 드릴다운", description = "특정 소스의 일자별 call_count. 빈 데이터면 빈 목록.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/ops/collection/{sourceId}")
    public ResponseEntity<ApiResponse<List<CollectionDetailItemResponse>>> collectionDetail(
            @Parameter(description = "소스 식별자") @PathVariable long sourceId,
            @Parameter(description = "기간(일, 기본 30)") @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(
                ApiResponse.success(opsStatsService.getCollectionDetail(sourceId, days)));
    }

    @Operation(summary = "감사 로그 조회", description = "action·actor·기간 필터, 시간 역순. 변형 액션 감사 추적.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/audit")
    public ResponseEntity<ApiResponse<Page<AuditLogItemResponse>>> audit(
            @Parameter(description = "행위 필터(예: ROLE_CHANGE)") @RequestParam(required = false) String action,
            @Parameter(description = "행위자 계정 필터") @RequestParam(required = false) UUID actorId,
            @Parameter(description = "시작 시각(ISO)") @RequestParam(required = false) Instant from,
            @Parameter(description = "종료 시각(ISO)") @RequestParam(required = false) Instant to,
            Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(opsStatsService.getAuditLogs(action, actorId, from, to, pageable)));
    }
}
