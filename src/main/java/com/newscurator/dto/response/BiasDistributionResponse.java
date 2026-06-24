package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 010 내 편향 분포 — 읽은 기사(006 DONE분)의 편향 비율(중립적 기술, 단정 라벨 없음).
 *
 * <p>버킷: 진보[-100,-34] / 중립[-33,33] / 보수[34,100]. 분석 완료 기사 0건이면 비율 null·total 0.
 */
@Schema(description = "내 편향 분포(중립 기술, %)")
public record BiasDistributionResponse(
        @Schema(description = "진보 비율 %(없으면 null)", example = "40.0") Double liberalPercent,
        @Schema(description = "중립 비율 %", example = "35.0") Double neutralPercent,
        @Schema(description = "보수 비율 %", example = "25.0") Double conservativePercent,
        @Schema(description = "편향 분석 완료 읽은 기사 수", example = "20") long total) {}
