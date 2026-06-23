package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "워드클라우드 항목. weight = 윈도우 내 term별 article_count 합(term-scoped, 과대계상 없음).")
public record WordcloudItemResponse(
        @Schema(description = "키워드", example = "금리")
        String term,

        @Schema(description = "가중치 = 윈도우 내 등장 기사 수 합", example = "12")
        long weight) {}
