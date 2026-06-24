package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.InsightResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.InsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 010 인사이트 + 추천 API — 인증 본인 스코프. */
@Tag(name = "Insights", description = "개인 소비 인사이트·놓친 기사 추천 API (010)")
@RestController
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @Operation(
            summary = "내 소비 인사이트 조회",
            description =
                    "읽은수·북마크수·최다카테고리·관심분포·주요언론사·내편향(읽은 기사 기준 온디맨드 집계). 본인 데이터만. "
                            + "읽은 고유 기사 < 5이면 sampleSufficient=false이고 분포 필드는 null(카운트는 항상 반환). "
                            + "편향은 중립적 분포 %(단정 라벨 없음).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping("/api/v1/me/insights")
    public ResponseEntity<ApiResponse<InsightResponse>> getInsights(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                ApiResponse.success(insightService.getInsights(userDetails.getAccountId())));
    }
}
