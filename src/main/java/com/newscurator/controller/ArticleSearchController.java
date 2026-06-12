package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ArticleSearchResponse;
import com.newscurator.exception.EmailNotVerifiedException;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.SearchService;
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

@Tag(name = "Search", description = "기사 전문 검색 (pg_bigm 한국어 지원)")
@Validated
@RestController
@RequestMapping("/api/v1/articles/search")
public class ArticleSearchController {

    private final SearchService searchService;

    public ArticleSearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
            summary = "기사 검색",
            description = "pg_bigm GIN 인덱스 기반 한국어 전문 검색. "
                    + "제목·요약 내용을 대상으로 bigram 유사도 relevance 순 정렬. "
                    + "관심사·팔로우 키워드 미반영(FR-013). "
                    + "검색 범위는 최근 90일 기사로 제한.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색 결과 (0건 포함)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일 인증 미완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 검증 실패 (빈 쿼리, 1자, 101자 이상)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<ArticleSearchResponse>> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "검색어 (2~100자)", required = true)
            @RequestParam String q,
            @Parameter(description = "이전 응답의 nextCursor 값 (커서 기반 페이지네이션)")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 (1~50, 기본값 20)")
            @Min(1) @Max(50)
            @RequestParam(required = false, defaultValue = "20") int size) {

        if (!userDetails.isEmailVerified()) {
            throw new EmailNotVerifiedException("이메일 인증이 필요합니다");
        }

        ArticleSearchResponse response = searchService.search(
                userDetails.getAccountId(), q, cursor, size);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
