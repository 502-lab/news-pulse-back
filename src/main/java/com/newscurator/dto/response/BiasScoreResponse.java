package com.newscurator.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// SC-002: value·rationaleKeywords가 null이어도 항상 직렬화(글로벌 inclusion 설정과 무관, omit 금지)
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "편향 분석 점수. 분석 미완료(PENDING/PROCESSING)·실패(FAILED) 시 value·rationaleKeywords는 null이고 status로 상태를 전달한다.")
public record BiasScoreResponse(
        @Schema(description = "편향 점수 −100(극진보)~+100(극보수). DONE 상태에서만 non-null", example = "-45")
        Integer value,

        @Schema(description = "점수 근거 키워드 2~5개. DONE 상태에서만 non-null",
                example = "[\"편향적 프레이밍\", \"단일 시각\"]")
        List<String> rationaleKeywords,

        @Schema(description = "분석 상태", example = "DONE",
                allowableValues = {"PENDING", "PROCESSING", "DONE", "FAILED"})
        String status) {}
