package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.service.BiasAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bias", description = "편향 분석 점수 조회 API")
@RestController
@RequestMapping("/api/v1/articles")
public class BiasController {

    private final BiasAnalysisService biasAnalysisService;

    public BiasController(BiasAnalysisService biasAnalysisService) {
        this.biasAnalysisService = biasAnalysisService;
    }

    @Operation(
            summary = "기사 편향 점수 조회 (편향 칩)",
            description = "기사 상세 화면에서 편향 칩 인터랙션 시 편향 점수와 근거 키워드를 반환합니다. "
                    + "분석 미완료(PENDING/PROCESSING)·실패(FAILED) 기사는 value·rationaleKeywords가 null이고 "
                    + "status로 상태를 전달합니다. BiasAnalysis 행이 없으면 404. 인증(JWT) 필수.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "편향 점수 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음/만료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 또는 편향 분석 데이터 없음")
    })
    @GetMapping("/{articleId}/bias")
    public ResponseEntity<ApiResponse<BiasScoreResponse>> getArticleBias(
            @Parameter(description = "기사 ID", required = true)
            @PathVariable Long articleId) {
        return ResponseEntity.ok(
                ApiResponse.success(biasAnalysisService.getBiasForArticle(articleId)));
    }
}
