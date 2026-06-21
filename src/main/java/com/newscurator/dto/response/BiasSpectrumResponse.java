package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "전체 편향 스펙트럼 응답. 분석완료 기사가 없으면 집계 값은 모두 null, totalCount=0.")
public record BiasSpectrumResponse(
        @Schema(description = "전체 분석완료 기사 가중평균. 기사 없으면 null", example = "-12.3")
        Double weightedAverage,

        @Schema(description = "진보[−100,−34] 비율 %. 기사 없으면 null", example = "42.5")
        Double liberalPercent,

        @Schema(description = "중립[−33,+33] 비율 %. 기사 없으면 null", example = "38.2")
        Double neutralPercent,

        @Schema(description = "보수[+34,+100] 비율 %. 기사 없으면 null", example = "19.3")
        Double conservativePercent,

        @Schema(description = "집계 대상 분석완료 기사 수", example = "5123")
        long totalCount) {}
