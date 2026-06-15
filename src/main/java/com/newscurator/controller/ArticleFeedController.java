package com.newscurator.controller;

import com.newscurator.dto.request.FeedRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ArticleFeedResponse;
import com.newscurator.service.ArticleFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Article Feed", description = "뉴스 피드 목록 조회 API")
@Validated
@RestController
@RequestMapping("/api/v1/articles")
public class ArticleFeedController {

    private final ArticleFeedService articleFeedService;

    public ArticleFeedController(ArticleFeedService articleFeedService) {
        this.articleFeedService = articleFeedService;
    }

    @Operation(
            summary = "뉴스 피드 목록 조회",
            description = "커서 기반 페이지네이션으로 뉴스 목록을 반환합니다. "
                    + "category_status가 COMPLETED 또는 FAILED인 기사만 포함되며, PENDING 기사는 노출되지 않습니다. "
                    + "분류 실패(FAILED)된 기사는 category가 OTHER로 표시됩니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 파라미터 (size 음수, 유효하지 않은 category 등)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<ArticleFeedResponse>> getFeed(
            @Parameter(description = "페이지네이션 커서 토큰. 첫 페이지 요청 시 생략")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기. 기본값 20, 최대 100 (초과 시 100으로 clamp)")
            @Positive @RequestParam(required = false) Integer size,
            @Parameter(description = "카테고리 필터. 생략 시 전체 조회. 가능한 값: ECONOMY_FINANCE, SCIENCE, POLITICS, SPORTS, WORLD, ENTERTAINMENT_CULTURE, HEALTH_MEDICINE, AUTOMOTIVE, IT, OTHER")
            @RequestParam(required = false) String category) {

        // category enum 유효성: Spring 자동 변환 실패 시
        // GlobalExceptionHandler의 MethodArgumentTypeMismatchException 핸들러 → 400 VALIDATION_ERROR (CHK028)
        com.newscurator.domain.enums.Category categoryEnum = null;
        if (category != null) {
            try {
                categoryEnum = com.newscurator.domain.enums.Category.valueOf(category.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        category, com.newscurator.domain.enums.Category.class, "category", null, e);
            }
        }

        FeedRequest request = new FeedRequest(cursor, size, categoryEnum);
        return ResponseEntity.ok(ApiResponse.success(articleFeedService.getFeed(request)));
    }
}
