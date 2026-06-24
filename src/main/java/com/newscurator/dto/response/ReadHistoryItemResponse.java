package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 009 조회 이력 항목 — article 기준 최신 1건(같은 기사 다회 조회는 1건). */
@Schema(description = "조회 이력 항목(article 기준 최신 1건)")
public record ReadHistoryItemResponse(
        @Schema(description = "기사 ID", example = "12345") Long articleId,
        @Schema(description = "기사 제목") String title,
        @Schema(description = "가장 최근 조회 시각(UTC)") OffsetDateTime lastViewedAt) {}
