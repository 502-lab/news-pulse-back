package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.HeatmapCellResponse;
import com.newscurator.dto.response.TrendKeywordResponse;
import com.newscurator.dto.response.WordcloudItemResponse;
import com.newscurator.service.TrendQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Trends", description = "트렌드 조회 API (공개)")
@RestController
public class TrendController {

    private final TrendQueryService trendQueryService;

    public TrendController(TrendQueryService trendQueryService) {
        this.trendQueryService = trendQueryService;
    }

    @Operation(
            summary = "지금 뜨는 키워드 Top5",
            description = "최근 24시간 윈도우 기준 급상승 키워드 상위 5개. 공개(인증 불필요). "
                    + "정렬은 평활비 기반, deltaPct는 raw %(prev=0이면 null+isNew). 노이즈컷: 등장 기사 2건 미만 제외.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(데이터 없으면 빈 목록)")
    })
    @GetMapping("/api/v1/trends/keywords/top5")
    public ResponseEntity<ApiResponse<List<TrendKeywordResponse>>> getTop5(
            @Parameter(description = "카테고리 필터(미지정 시 전체). 분류 실패 기사는 OTHER로 집계")
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(ApiResponse.success(trendQueryService.getTop5(category)));
    }

    @Operation(
            summary = "WoW(주간 대비) 급상승",
            description = "이번 주(최근 7일) vs 지난주(7~14일 전) 급상승 키워드. 공개(인증 불필요). "
                    + "정렬은 평활비 (cur+1)/(prev+1) 기반, deltaPct는 raw %. prev=0 → deltaPct null + isNew. "
                    + "cur<2 제외. 24h Top5와의 구분점은 주간 윈도우 경계.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(데이터 없으면 빈 목록)")
    })
    @GetMapping("/api/v1/trends/wow")
    public ResponseEntity<ApiResponse<List<TrendKeywordResponse>>> getWow() {
        return ResponseEntity.ok(ApiResponse.success(trendQueryService.getWow()));
    }

    @Operation(
            summary = "히트맵 (시간버킷 × 카테고리)",
            description = "시간 슬롯 × 카테고리 격자의 기사 볼륨(intensity = DISTINCT 기사 수). 공개. "
                    + "per-term SUM이 아닌 DISTINCT 기사 수(과대계상 방지).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(빈 윈도우면 빈 배열)")
    })
    @GetMapping("/api/v1/trends/heatmap")
    public ResponseEntity<ApiResponse<List<HeatmapCellResponse>>> getHeatmap(
            @Parameter(description = "윈도우 시간(기본 24h)")
            @RequestParam(defaultValue = "24") int windowHours) {
        return ResponseEntity.ok(ApiResponse.success(trendQueryService.getHeatmap(windowHours)));
    }

    @Operation(
            summary = "워드클라우드",
            description = "윈도우 내 키워드별 가중치(weight = 등장 기사 수 합). 공개. min-freq(2건 미만) 제외.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(빈 윈도우면 빈 배열)")
    })
    @GetMapping("/api/v1/trends/wordcloud")
    public ResponseEntity<ApiResponse<List<WordcloudItemResponse>>> getWordcloud(
            @Parameter(description = "윈도우 시간(기본 24h)")
            @RequestParam(defaultValue = "24") int windowHours) {
        return ResponseEntity.ok(ApiResponse.success(trendQueryService.getWordcloud(windowHours)));
    }
}
