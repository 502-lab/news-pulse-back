package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 설정 변경 요청")
public record NotificationSettingsRequest(
        @Schema(description = "푸시 알림 수신 여부", example = "true") boolean pushEnabled,
        @Schema(description = "이메일 알림 수신 여부", example = "true") boolean emailEnabled,
        @Schema(description = "급상승 키워드 알림 수신 여부", example = "true") boolean risingEnabled,
        @Schema(description = "편향 분석 알림 수신 여부", example = "true") boolean biasEnabled) {
}
