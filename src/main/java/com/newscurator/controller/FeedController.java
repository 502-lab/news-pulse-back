package com.newscurator.controller;

import com.newscurator.domain.enums.Category;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.FeedResponse;
import com.newscurator.exception.EmailNotVerifiedException;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feed", description = "개인화 뉴스 피드")
@Validated
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @Operation(
            summary = "개인화 피드 조회",
            description = "사용자의 관심 카테고리·팔로우 키워드·최신성 기반 규칙 가중치로 랭킹된 기사 목록 반환. "
                    + "관심사 미설정 시 최신순 fallback (personalized=false).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "피드 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일 인증 미완료")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<FeedResponse>> getFeed(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "카테고리 필터. 미지정 시 전체 관심사 기반 추천")
            @RequestParam(required = false) String category,
            @Parameter(description = "이전 응답의 nextCursor 값")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 (1-50, 기본값 20)")
            @Min(1) @Max(50) @RequestParam(required = false, defaultValue = "20") int size) {

        if (!userDetails.isEmailVerified()) {
            throw new EmailNotVerifiedException("이메일 인증이 필요합니다");
        }

        Category categoryEnum = parseCategory(category);

        FeedResponse feedResponse = feedService.getFeed(
                userDetails.getAccountId(), cursor, size, categoryEnum);

        return ResponseEntity.ok(ApiResponse.success(feedResponse));
    }

    private Category parseCategory(String category) {
        if (category == null) return null;
        try {
            return Category.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                    category, Category.class, "category", null, e);
        }
    }
}
