package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 에러 로그(008 FR-051) — 기존 FAILED 상태 집계(신규 테이블 없음). 출처별 카운트.
 */
@Schema(description = "에러 로그(FAILED 집계)")
public record ErrorLogResponse(
        @Schema(description = "요약 실패(articles.summary_status=FAILED)") long summaryFailed,
        @Schema(description = "편향 분석 실패(bias_analysis.status=FAILED)") long biasFailed,
        @Schema(description = "알림 발송 실패(notification_outbox.status=FAILED)") long notificationFailed,
        @Schema(description = "합계") long total) {

    public static ErrorLogResponse of(long summary, long bias, long notification) {
        return new ErrorLogResponse(summary, bias, notification, summary + bias + notification);
    }
}
