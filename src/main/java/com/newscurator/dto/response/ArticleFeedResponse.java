package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "뉴스 피드 목록 응답")
public record ArticleFeedResponse(
        @Schema(description = "기사 목록")
        List<ArticleFeedItem> data,

        @Schema(description = "다음 페이지 커서 토큰. 마지막 페이지면 null")
        String nextCursor,

        @Schema(description = "다음 페이지 존재 여부")
        boolean hasMore,

        @Schema(description = "현재 페이지 기사 수")
        int size) {}
