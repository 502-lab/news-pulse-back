package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 스케줄러 토글 요청(008 FR-031). enabled만 — interval은 MVP 미수용(거짓약속 회피, analyze U1).
 */
@Schema(description = "스케줄러 활성/비활성 토글 요청")
public record SchedulerToggleRequest(
        @Schema(description = "활성 여부", example = "false") @NotNull Boolean enabled) {}
