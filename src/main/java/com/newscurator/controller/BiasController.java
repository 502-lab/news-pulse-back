package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.BackfillResult;
import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.dto.response.BiasSpectrumResponse;
import com.newscurator.dto.response.OutletBiasResponse;
import com.newscurator.service.BiasAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bias", description = "편향 분석 점수·집계 조회 API")
@RestController
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
    @GetMapping("/api/v1/articles/{articleId}/bias")
    public ResponseEntity<ApiResponse<BiasScoreResponse>> getArticleBias(
            @Parameter(description = "기사 ID", required = true)
            @PathVariable Long articleId) {
        return ResponseEntity.ok(
                ApiResponse.success(biasAnalysisService.getBiasForArticle(articleId)));
    }

    @Operation(
            summary = "출처 편향 집계 조회",
            description = "특정 출처(Source)의 편향 점수 단순평균과 분석 완료 기사 수를 반환합니다. "
                    + "집계 기준: 롤링 90일, status=DONE 기사만. 분석완료 10건 미만이면 biasValue=null. 인증(JWT) 필수.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "출처 편향 집계 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음/만료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "출처 없음")
    })
    @GetMapping("/api/v1/bias/outlets/{sourceId}")
    public ResponseEntity<ApiResponse<OutletBiasResponse>> getOutletBias(
            @Parameter(description = "출처(Source) ID", required = true)
            @PathVariable Long sourceId) {
        return ResponseEntity.ok(
                ApiResponse.success(biasAnalysisService.getOutletBias(sourceId)));
    }

    @Operation(
            summary = "전체 편향 스펙트럼 조회",
            description = "서비스 전체 분석완료 기사의 편향 분포(가중평균, 진보/중립/보수 %)를 반환합니다. "
                    + "버킷: 진보[−100,−34] / 중립[−33,+33] / 보수[+34,+100]. 분석완료 기사가 없으면 모든 값 null. 인증(JWT) 필수.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "스펙트럼 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음/만료")
    })
    @GetMapping("/api/v1/bias/spectrum")
    public ResponseEntity<ApiResponse<BiasSpectrumResponse>> getSpectrum() {
        return ResponseEntity.ok(ApiResponse.success(biasAnalysisService.getSpectrum()));
    }

    @Operation(
            summary = "편향 분석 Backfill 실행 (관리자)",
            description = "최근 90일 이내 수집 기사 중 bias_analysis 행이 없는 기사에 PENDING 행을 일괄 생성합니다. "
                    + "멱등(ON CONFLICT DO NOTHING). 별도 버스트가 아니라 PENDING만 생성하고 정상 claimer가 "
                    + "rate-safe하게 드레인하므로 즉시 202를 반환합니다. ROLE_ADMIN 필수.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Backfill PENDING 행 생성 완료(드레인은 비동기)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음/만료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @PostMapping("/api/v1/admin/bias/backfill")
    public ResponseEntity<ApiResponse<BackfillResult>> triggerBackfill() {
        long created = biasAnalysisService.backfill();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.accepted(new BackfillResult(created)));
    }
}
