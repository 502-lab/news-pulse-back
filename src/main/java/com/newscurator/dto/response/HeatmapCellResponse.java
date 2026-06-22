package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "히트맵 셀 (시간버킷 × 카테고리). intensity = 해당 슬롯·카테고리의 DISTINCT 기사 수(기사 볼륨).")
public record HeatmapCellResponse(
        @Schema(description = "시간 슬롯 시작(시간 버킷, UTC)")
        OffsetDateTime slotStart,

        @Schema(description = "카테고리(분류 실패는 OTHER)", example = "POLITICS")
        String category,

        @Schema(description = "기사 볼륨 = DISTINCT 기사 수(per-term SUM 아님)", example = "12")
        long intensity) {}
