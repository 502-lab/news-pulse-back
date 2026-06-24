package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.ArticleDetailService;
import com.newscurator.service.ReadTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Article Detail", description = "기사 상세 조회 API")
@RestController
@RequestMapping("/api/v1/articles")
public class ArticleDetailController {

    private final ArticleDetailService articleDetailService;
    private final ReadTrackingService readTrackingService;

    public ArticleDetailController(
            ArticleDetailService articleDetailService, ReadTrackingService readTrackingService) {
        this.articleDetailService = articleDetailService;
        this.readTrackingService = readTrackingService;
    }

    @Operation(
            summary = "기사 상세 조회",
            description = "기사 ID로 상세 정보와 요약 슬롯(brief / balanced / deep)을 반환합니다. "
                    + "deep 요약은 최초 상세 조회 시 lazy 생성되므로 첫 요청에서는 status가 PENDING일 수 있습니다. "
                    + "인증 사용자의 조회는 best-effort로 기록됩니다(009, 30분 디바운스). 기록 실패는 상세 응답에 영향 없음.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 ID의 기사가 존재하지 않음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ArticleDetailResponse>> getDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "기사 ID", required = true) @PathVariable Long id) {
        // 상세 조회(서비스 @Transactional)가 먼저 반환·커밋된 뒤 기록 — best-effort 격리.
        ApiResponse<ArticleDetailResponse> body =
                ApiResponse.success(articleDetailService.getDetail(id));
        // 009: 인증 사용자만 조회 기록(D3 비로그인 미기록). 예외는 삼켜 핫패스 비차단(FR-003 실패 격리).
        if (userDetails != null) {
            try {
                readTrackingService.recordView(userDetails.getAccountId(), id);
            } catch (Exception e) {
                log.warn("read tracking failed for article {}: {}", id, e.toString());
            }
        }
        return ResponseEntity.ok(body);
    }
}
