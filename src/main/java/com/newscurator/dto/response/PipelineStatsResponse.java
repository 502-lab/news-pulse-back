package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Map;

@Schema(description = "파이프라인 통계 응답")
public record PipelineStatsResponse(
        @Schema(description = "통계 기준 날짜 (오늘, UTC)")
        LocalDate date,

        @Schema(description = "오늘 수집된 기사 수")
        long articlesCollectedToday,

        @Schema(description = "요약 완료율 (0.0 ~ 1.0). 전체 기사 중 summary_status=COMPLETED 비율")
        double summaryCompletionRate,

        @Schema(description = "중복 병합 건수 (동일 URL 기사가 다른 출처에서 중복 수집된 횟수)")
        long mergeCount,

        @Schema(description = "카테고리별 기사 수. 키: 카테고리명, 값: 기사 수")
        Map<String, Long> categoryBreakdown,

        @Schema(description = "파이프라인 처리 현황")
        PipelineStatus pipelineStatus) {

    @Schema(description = "파이프라인 처리 대기/실패 현황")
    public record PipelineStatus(
            @Schema(description = "카테고리 분류 대기 중인 기사 수")
            long categoryPending,

            @Schema(description = "카테고리 분류 영구 실패한 기사 수")
            long categoryFailed,

            @Schema(description = "요약 생성 대기 중인 기사 수")
            long summaryPending,

            @Schema(description = "요약 생성 영구 실패한 기사 수")
            long summaryFailed) {}
}
