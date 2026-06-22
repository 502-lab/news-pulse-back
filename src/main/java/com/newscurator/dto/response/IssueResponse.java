package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "이슈(co-occurrence 클러스터). 매 집계 재산출되며 cross-run 안정 ID는 없다.")
public record IssueResponse(
        @Schema(description = "대표 키워드(top3)", example = "[\"금리\", \"인상\", \"경제\"]")
        List<String> keywords,

        @Schema(description = "관련 기사 id 묶음")
        List<Long> articleIds,

        @Schema(description = "증감(멤버 키워드 WoW delta 평균). 산출 불가면 null", nullable = true)
        Double delta,

        @Schema(description = "클러스터링 방식", example = "CO_OCCURRENCE")
        String clusteringMethod) {}
