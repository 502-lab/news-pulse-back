package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "TTS 생성 요청")
public record TtsRequest(
        @Schema(description = "AWS Polly VoiceId", example = "Seoyeon")
        @NotBlank String voiceId
) {}
