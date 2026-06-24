package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 009 조회 이력 페이지(커서 기반, lastViewedAt 역순). */
@Schema(description = "조회 이력 페이지")
public record ReadHistoryListResponse(
        @Schema(description = "이력 항목(최신순)") List<ReadHistoryItemResponse> items,
        @Schema(description = "다음 페이지 커서(없으면 null). 다음 요청의 cursor로 전달") String nextCursor,
        @Schema(description = "다음 페이지 존재 여부") boolean hasNext) {}
