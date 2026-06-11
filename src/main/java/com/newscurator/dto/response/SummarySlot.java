package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "요약 슬롯")
public record SummarySlot(
        @Schema(description = "요약 상태", allowableValues = {"NOT_GENERATED", "PENDING", "COMPLETED", "FAILED"})
        String status,

        @Schema(description = "요약 내용. COMPLETED 상태일 때만 존재하며 FAILED 시 null")
        String content,

        @Schema(description = "요약 생성 완료 시각 (UTC). COMPLETED 상태일 때만 존재")
        OffsetDateTime generatedAt) {}
