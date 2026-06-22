package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "편향 분석 Backfill 실행 결과")
public record BackfillResult(
        @Schema(description = "새로 생성된 PENDING 행 수 (이미 존재하던 기사는 ON CONFLICT로 제외)",
                example = "5494")
        long created) {}
