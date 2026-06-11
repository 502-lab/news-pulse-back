package com.newscurator.controller;

import com.newscurator.dto.response.PipelineStatsResponse;
import com.newscurator.service.PipelineStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /admin/* 경로 분리 (향후 ROLE_ADMIN 보호를 위해; spec 002에서 인증 적용)
// 현재는 Nginx HTTP Basic Auth 또는 IP 허용목록으로 임시 보호 (plan.md Complexity Tracking)
@Tag(name = "Admin", description = "관리자 전용 API. 추후 ROLE_ADMIN 인증 적용 예정 (spec 002)")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPipelineController {

    private final PipelineStatsService pipelineStatsService;

    public AdminPipelineController(PipelineStatsService pipelineStatsService) {
        this.pipelineStatsService = pipelineStatsService;
    }

    @Operation(
            summary = "파이프라인 통계 조회",
            description = "오늘 날짜 기준 수집 건수, 요약 완료율, 중복 병합 건수, 카테고리별 분포, "
                    + "파이프라인 처리 대기/실패 현황을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공",
                content = @Content(schema = @Schema(implementation = PipelineStatsResponse.class)))
    })
    @GetMapping("/pipeline/stats")
    public ResponseEntity<PipelineStatsResponse> getStats() {
        return ResponseEntity.ok(pipelineStatsService.getStats());
    }
}
