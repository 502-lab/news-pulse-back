package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 010 놓친 기사 추천(US2) — 관심사·조회 프로파일·트렌드 가중으로 정렬된 미열람 기사.
 *
 * <p>{@code coldStart=true}이면 조회 이력·관심사가 둘 다 없어 트렌드/최근 인기 fallback으로 채운 결과다.
 */
@Schema(description = "놓친 기사 추천")
public record RecommendationResponse(
        @Schema(description = "추천 기사 목록") List<RecommendedArticle> items,
        @Schema(description = "콜드스타트 fallback 여부(조회·관심사 0 → 트렌드)", example = "false")
                boolean coldStart) {

    @Schema(description = "추천 기사 항목")
    public record RecommendedArticle(
            @Schema(description = "기사 ID", example = "12345") Long articleId,
            @Schema(description = "제목") String title,
            @Schema(description = "카테고리", nullable = true) String category,
            @Schema(description = "발행 시각(UTC)") OffsetDateTime publishedAt,
            @Schema(description = "추천 사유", example = "INTEREST_MATCH") String reason) {}
}
