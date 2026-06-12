package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "기사 검색 요청 파라미터")
public record ArticleSearchRequest(
        @Schema(description = "검색어 (2~100자)", example = "경제성장", required = true)
        @NotBlank
        @Size(min = 2, max = 100)
        String q,

        @Schema(description = "이전 응답의 nextCursor 값 (커서 기반 페이지네이션)", nullable = true)
        String cursor,

        @Schema(description = "페이지 크기 (1~50, 기본값 20)", example = "20")
        @Min(1) @Max(50)
        int size) {}
