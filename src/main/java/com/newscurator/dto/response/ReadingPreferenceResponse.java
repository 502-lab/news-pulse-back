package com.newscurator.dto.response;

import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.SummaryDepth;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "읽기 방식 응답")
public record ReadingPreferenceResponse(
        @Schema(description = "요약 깊이") SummaryDepth summaryDepth,
        @Schema(description = "소비 방식") ConsumeMode consumeMode
) {}
