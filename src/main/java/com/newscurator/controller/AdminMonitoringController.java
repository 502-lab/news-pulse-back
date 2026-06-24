package com.newscurator.controller;

import com.newscurator.dto.response.AdminKpiResponse;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.BiasAdminViewResponse;
import com.newscurator.dto.response.CollectionVolumeResponse;
import com.newscurator.dto.response.SchedulerStatusResponse;
import com.newscurator.dto.response.TrendAdminViewResponse;
import com.newscurator.service.admin.AdminMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 운영 모니터링 조회 API(008 US2). read-only. 전 경로 {@code /api/v1/admin/**} → hasRole(ADMIN).
 * 파이프라인 상태는 기존 {@code AdminPipelineController GET /api/v1/admin/pipeline/stats} 재활용.
 */
@Tag(name = "Admin Monitoring", description = "어드민 운영 모니터링 조회 API (ADMIN 전용, 읽기 전용)")
@RestController
public class AdminMonitoringController {

    private final AdminMonitoringService monitoringService;

    public AdminMonitoringController(AdminMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @Operation(summary = "핵심 KPI", description = "총·활성 사용자·수집기사·요약/편향 완료율·트렌드 이슈 수. 빈 데이터 시 0/0.0.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 아님")
    })
    @GetMapping("/api/v1/admin/monitoring/kpi")
    public ResponseEntity<ApiResponse<AdminKpiResponse>> kpi() {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.getKpi()));
    }

    @Operation(summary = "스케줄러 상태", description = "12 스케줄러의 enabled 상태 + 갱신 메타.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/monitoring/schedulers")
    public ResponseEntity<ApiResponse<List<SchedulerStatusResponse>>> schedulers() {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.getSchedulerStatuses()));
    }

    @Operation(summary = "소스별 수집량", description = "최근 days일 source_daily_usage.call_count 합. 빈 데이터 시 빈 목록.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/monitoring/collection")
    public ResponseEntity<ApiResponse<List<CollectionVolumeResponse>>> collection(
            @Parameter(description = "집계 기간(일, 기본 7)") @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.getCollectionVolume(days)));
    }

    @Operation(summary = "편향 어드민 뷰", description = "전역 편향 분석 분포. ★ hidden 기사 포함(admin_hidden_at 필터 안 함).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/monitoring/bias")
    public ResponseEntity<ApiResponse<BiasAdminViewResponse>> bias() {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.getBiasView()));
    }

    @Operation(summary = "트렌드 어드민 뷰", description = "이슈·키워드 집계. ★ hidden 기사 포함(admin_hidden_at 필터 안 함).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/monitoring/trend")
    public ResponseEntity<ApiResponse<TrendAdminViewResponse>> trend() {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.getTrendView()));
    }
}
