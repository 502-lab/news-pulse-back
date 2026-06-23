package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 스케줄러 상태 항목(008 US2/FR-023). 12키 각 enabled + 갱신 메타.
 */
@Schema(description = "스케줄러 상태")
public record SchedulerStatusResponse(
        @Schema(description = "스케줄러 키", example = "trend_aggregation") String schedulerKey,
        @Schema(description = "활성 여부") boolean enabled,
        @Schema(description = "마지막 변경 시각") Instant updatedAt) {}
