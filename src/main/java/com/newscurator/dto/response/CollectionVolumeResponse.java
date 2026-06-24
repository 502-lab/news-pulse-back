package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 소스별 수집량(008 US2/FR-022). source_daily_usage.call_count 합. 빈 데이터 시 빈 목록.
 */
@Schema(description = "소스별 수집량")
public record CollectionVolumeResponse(
        @Schema(description = "소스 식별자") long sourceId,
        @Schema(description = "기간 내 총 호출 수") long totalCalls) {}
