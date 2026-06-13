package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "데일리 브리핑 TTS 응답")
public record BriefingResponse(
        @Schema(description = "브리핑 날짜", example = "2026-06-13") LocalDate briefDate,
        @Schema(description = "재생 큐 기사 ID 순서 (summaryStatus=COMPLETED 기사만 포함)") List<Long> articleIds,
        @Schema(description = "사용 음성 ID", example = "harin") String voiceId,
        @Schema(description = "각 기사 TTS 상태 목록. 전체 READY이면 HTTP 200, 아니면 202 반환.") List<TtsStatusResponse> ttsItems
) {}
