package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 어드민 트렌드(007) 요약 뷰(008 US2). ★ hidden 기사 포함(admin_hidden_at 필터 안 함).
 */
@Schema(description = "어드민 트렌드 요약 뷰(hidden 포함)")
public record TrendAdminViewResponse(
        @Schema(description = "이슈 스냅샷 수") long issueCount,
        @Schema(description = "키워드 추출된 기사 수(hidden 포함)") long keywordedArticlesIncludingHidden) {}
