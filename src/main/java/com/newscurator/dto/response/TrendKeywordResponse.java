package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "트렌드 키워드 항목 (Top5/WoW). slots[] 시계열은 MVP 후순위/deferred — FR-005.")
public record TrendKeywordResponse(
        @Schema(description = "키워드", example = "금리")
        String term,

        @Schema(description = "윈도우 내 등장 기사 수 합", example = "12")
        long count,

        @Schema(description = "raw 증감률 %((cur-prev)/prev). prev=0이면 null", example = "50.0", nullable = true)
        Double deltaPct,

        @Schema(description = "직전 윈도우 0건 신규 키워드", example = "false")
        boolean isNew) {}
