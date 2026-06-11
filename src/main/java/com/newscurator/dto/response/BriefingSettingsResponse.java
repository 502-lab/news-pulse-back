package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalTime;

@Schema(description = "브리핑 설정 응답")
public record BriefingSettingsResponse(
        @Schema(description = "브리핑 시각") LocalTime briefingTime,
        @Schema(description = "타임존 오프셋(분)") Short timezoneOffset,
        @Schema(description = "음성 브리핑 활성화") boolean voiceEnabled,
        @Schema(description = "푸시 알림 동의") boolean pushAgreed,
        @Schema(description = "푸시 알림 동의 시각") Instant pushAgreedAt
) {}
