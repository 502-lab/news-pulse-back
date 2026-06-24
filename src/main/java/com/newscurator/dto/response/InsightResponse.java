package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 010 개인 소비 인사이트(US1) — 온디맨드 집계, 본인 스코프.
 *
 * <p>카운트(readCount·bookmarkCount)는 항상 반환. 읽은 고유 기사 < minSampleSize(5)이면
 * {@code sampleSufficient=false}이고 분포 필드는 모두 null(표본 부족 — 분포 미산출).
 */
@Schema(description = "개인 소비 인사이트")
public record InsightResponse(
        @Schema(description = "읽은 고유 기사 수(distinct)", example = "42") long readCount,
        @Schema(description = "북마크 수", example = "7") long bookmarkCount,
        @Schema(description = "통계 표본 충분 여부(읽은 고유 기사 ≥ 5)", example = "true")
                boolean sampleSufficient,
        @Schema(description = "최다 카테고리(표본 부족 시 null)", nullable = true) String topCategory,
        @Schema(description = "카테고리 분포(표본 부족 시 null)", nullable = true)
                List<CategoryShare> categoryDistribution,
        @Schema(description = "관심 키워드 분포(표본 부족 시 null)", nullable = true)
                List<KeywordCount> keywordDistribution,
        @Schema(description = "주요 언론사(표본 부족 시 null)", nullable = true)
                List<OutletCount> topOutlets,
        @Schema(description = "내 편향 분포(표본 부족 시 null)", nullable = true)
                BiasDistributionResponse biasDistribution) {

    @Schema(description = "카테고리별 비중")
    public record CategoryShare(
            @Schema(description = "카테고리", example = "TECH") String category,
            @Schema(description = "비중 %", example = "35.0") double percent) {}

    @Schema(description = "키워드별 조회 빈도")
    public record KeywordCount(
            @Schema(description = "키워드") String keyword,
            @Schema(description = "빈도", example = "5") long count) {}

    @Schema(description = "언론사별 조회 빈도")
    public record OutletCount(
            @Schema(description = "언론사명") String name,
            @Schema(description = "빈도", example = "8") long count) {}
}
