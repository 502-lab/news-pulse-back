package com.newscurator.dto.response;

import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "TTS 오디오 상태 응답")
public record TtsStatusResponse(
        @Schema(description = "TTS 오디오 UUID") UUID id,
        @Schema(description = "오너 타입 (ARTICLE)") TtsOwnerType ownerType,
        @Schema(description = "기사 ID (문자열)") String refId,
        @Schema(description = "음성 ID", example = "harin") String voiceId,
        @Schema(description = "처리 상태 (PENDING/PROCESSING/READY/FAILED)") TtsStatus status,
        @Schema(description = "CloudFront 오디오 URL. READY 상태일 때만 non-null.") String audioUrl,
        @Schema(description = "오디오 재생 시간 (초). Naver API 미제공 시 null.") Integer durationSec,
        @Schema(description = "오류 메시지. FAILED 상태일 때만 non-null.") String errorMsg
) {}
