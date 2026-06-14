package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "기사 요약 슬롯 (선호 depth 우선, 없으면 fallback)")
public record FeedSummarySlot(
        @Schema(description = "요약 본문. null이면 요약 미생성 상태", nullable = true,
                example = "미국 연준이 금리를 동결하면서 시장에 안도감이 퍼졌다.")
        String text,

        @Schema(description = "실제 반환된 슬롯 depth", example = "balanced",
                allowableValues = {"brief", "balanced", "deep"})
        String depth,

        @Schema(description = "선호 depth와 실제 반환 depth가 다른 경우 true", example = "true")
        boolean isFallback) {}
