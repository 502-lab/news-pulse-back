package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "출처(Outlet) 편향 집계 응답")
public record OutletBiasResponse(
        @Schema(description = "출처 ID", example = "1")
        Long sourceId,

        @Schema(description = "편향 점수 단순평균 (롤링 90일, 분석완료 10건 이상 시). 미달이면 null",
                example = "-23.5")
        Double biasValue,

        @Schema(description = "분석 완료 기사 수 (롤링 90일)", example = "142")
        long articleCount) {}
