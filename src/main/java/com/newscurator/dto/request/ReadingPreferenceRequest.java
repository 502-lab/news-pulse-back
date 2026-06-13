package com.newscurator.dto.request;

import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.SummaryDepth;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "읽기 방식 수정 요청")
public record ReadingPreferenceRequest(
        @Schema(description = "요약 깊이", example = "BALANCED") @NotNull SummaryDepth summaryDepth,
        @Schema(description = "소비 방식", example = "READ") @NotNull ConsumeMode consumeMode,
        @Schema(description = "선호 음성 ID. null = app.tts.default-voice-id 설정값 사용", example = "harin")
                String voiceId
) {}
