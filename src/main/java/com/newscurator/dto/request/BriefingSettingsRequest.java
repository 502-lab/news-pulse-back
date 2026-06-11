package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

@Schema(description = "브리핑 설정 수정 요청")
public record BriefingSettingsRequest(
        @Schema(description = "브리핑 시각 (HH:mm)", example = "08:00") LocalTime briefingTime,
        @Schema(description = "타임존 오프셋(분)", example = "540") Short timezoneOffset,
        @Schema(description = "음성 브리핑 활성화", example = "false") boolean voiceEnabled,
        @Schema(description = "푸시 알림 동의", example = "false") boolean pushAgreed
) {}
