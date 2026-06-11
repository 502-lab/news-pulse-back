package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "피드 목록 기사 항목")
public record ArticleFeedItem(
        @Schema(description = "기사 ID")
        Long id,

        @Schema(description = "기사 제목")
        String title,

        @Schema(description = "작성자. 없으면 null")
        String author,

        @Schema(description = "카테고리. 분류 실패 시 OTHER", example = "TECH")
        String category,

        @Schema(description = "기사 발행 시각 (UTC)")
        OffsetDateTime publishedAt,

        @Schema(description = "최초 수집 시각 (UTC)")
        OffsetDateTime firstCollectedAt,

        @Schema(description = "간략 요약 (피드 목록에서는 항상 null, 상세 조회에서 로드)")
        String briefSummary) {}
