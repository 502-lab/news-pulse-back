package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "피드/검색 공통 기사 응답")
public record ArticleItem(
        @Schema(description = "기사 ID", example = "1")
        Long id,

        @Schema(description = "기사 제목", example = "연준, 금리 동결 결정")
        String title,

        @Schema(description = "카테고리", example = "ECONOMY_FINANCE",
                allowableValues = {
                    "POLITICS", "ECONOMY_FINANCE", "ENTERTAINMENT_CULTURE", "SPORTS",
                    "WORLD", "SCIENCE", "HEALTH_MEDICINE", "AUTOMOTIVE", "IT", "OTHER"
                })
        String category,

        @Schema(description = "발행 시각 (UTC)", example = "2026-06-12T03:00:00Z")
        OffsetDateTime publishedAt,

        @Schema(description = "출처명 (article_sources → sources.name 조인, 복수 출처 시 첫 번째 수집 출처 반환)",
                example = "조선일보")
        String sourceName,

        @Schema(description = "요약 슬롯 (선호 depth fallback 포함)")
        FeedSummarySlot summary,

        @Schema(description = "개인화 랭킹 점수 (피드 응답에만 포함, null이면 미포함)", example = "80.5",
                nullable = true)
        Double rankScore,

        @Schema(description = "현재 사용자가 저장한 기사 여부", example = "false")
        boolean saved) {}
