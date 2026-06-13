package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "음성 캐릭터 정보")
public record VoiceResponse(
        @Schema(description = "Naver Clova Voice speaker ID", example = "harin") String id,
        @Schema(description = "표시명", example = "하린") String name,
        @Schema(description = "성별 (FEMALE | MALE)", example = "FEMALE") String gender,
        @Schema(description = "미리듣기 정적 샘플 URL (CDN). 미설정 시 null.") String previewUrl
) {}
