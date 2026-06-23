package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 어드민 핵심 KPI(008 US2). 전부 기존 테이블 집계. 빈 데이터 시 0/0.0(분모 0 가드).
 */
@Schema(description = "어드민 KPI 요약")
public record AdminKpiResponse(
        @Schema(description = "총 사용자 수") long totalUsers,
        @Schema(description = "활성 사용자 수") long activeUsers,
        @Schema(description = "총 수집 기사 수") long totalArticles,
        @Schema(description = "요약 완료율(%)") double summaryCompletionRate,
        @Schema(description = "편향 분석 완료율(%)") double biasCompletionRate,
        @Schema(description = "트렌드 이슈 수") long trendIssueCount) {}
