package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 어드민 편향(006) 요약 뷰(008 US2). ★ hidden 기사 포함(admin_hidden_at 필터 안 함).
 */
@Schema(description = "어드민 편향 요약 뷰(hidden 포함)")
public record BiasAdminViewResponse(
        @Schema(description = "총 분석 레코드 수") long total,
        @Schema(description = "완료(DONE)") long done,
        @Schema(description = "대기(PENDING/PROCESSING)") long pending,
        @Schema(description = "실패(FAILED)") long failed,
        @Schema(description = "분석 완료 기사 수(hidden 포함)") long analyzedArticlesIncludingHidden) {}
