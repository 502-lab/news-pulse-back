package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "저장된 기사 항목")
public record SavedArticleItem(
        @Schema(description = "저장 시각 (UTC)", example = "2026-06-13T00:00:00Z")
        Instant savedAt,

        @Schema(description = "기사 정보")
        ArticleItem article) {}
