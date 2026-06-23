package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** OpsStats 일자별 추이 항목(008 US5). 빈 윈도우면 빈 목록. */
@Schema(description = "운영 통계 일자별 항목")
public record OpsStatItemResponse(
        @Schema(description = "일자(UTC 자정)") Instant day,
        @Schema(description = "수집 기사 수") long collectedCount) {}
