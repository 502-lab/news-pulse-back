package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.TrendKeywordResponse;
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
}
