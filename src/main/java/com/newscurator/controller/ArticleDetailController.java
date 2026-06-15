package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.service.ArticleDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Article Detail", description = "기사 상세 조회 API")
@RestController
@RequestMapping("/api/v1/articles")
public class ArticleDetailController {

    private final ArticleDetailService articleDetailService;

    public ArticleDetailController(ArticleDetailService articleDetailService) {
        this.articleDetailService = articleDetailService;
    }

    @Operation(
            summary = "기사 상세 조회",
            description = "기사 ID로 상세 정보와 요약 슬롯(brief / balanced / deep)을 반환합니다. "
                    + "deep 요약은 최초 상세 조회 시 lazy 생성되므로 첫 요청에서는 status가 PENDING일 수 있습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 ID의 기사가 존재하지 않음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ArticleDetailResponse>> getDetail(
            @Parameter(description = "기사 ID", required = true)
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(articleDetailService.getDetail(id)));
    }
}
