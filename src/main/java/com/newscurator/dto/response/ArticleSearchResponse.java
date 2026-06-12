package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "기사 검색 응답")
public record ArticleSearchResponse(
        @Schema(description = "검색 결과 기사 목록 (relevance 내림차순)")
        List<ArticleItem> articles,

        @Schema(description = "다음 페이지 커서. null이면 마지막 페이지", nullable = true)
        String nextCursor,

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        boolean hasNext) {}
