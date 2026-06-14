package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "저장 기사 목록 응답")
public record SavedArticleListResponse(
        @Schema(description = "저장 기사 목록 (savedAt 역순)")
        List<SavedArticleItem> articles,

        @Schema(description = "다음 페이지 커서. null이면 마지막 페이지", nullable = true)
        String nextCursor,

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        boolean hasNext) {}
