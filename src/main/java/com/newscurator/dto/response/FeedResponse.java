package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "개인화 피드 응답")
public record FeedResponse(
        @Schema(description = "기사 목록")
        List<ArticleItem> articles,

        @Schema(description = "다음 페이지 커서. 마지막 페이지면 null", nullable = true,
                example = "eyJzY29yZSI6ODAuNX0=")
        String nextCursor,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext,

        @Schema(description = "현재 페이지 기사 수", example = "20")
        int size,

        @Schema(description = "false이면 관심사 기반 랭킹 미적용(최신순 fallback)", example = "true")
        boolean personalized) {}
