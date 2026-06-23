package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 소스 수집량 드릴다운 항목(008 US5/FR-052). 빈 데이터면 빈 목록. */
@Schema(description = "소스 수집량 일자별 항목")
public record CollectionDetailItemResponse(
        @Schema(description = "일자") LocalDate usageDate,
        @Schema(description = "호출 수") long callCount) {}
