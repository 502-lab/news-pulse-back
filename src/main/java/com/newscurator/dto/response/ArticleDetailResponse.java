package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "기사 상세 응답")
public record ArticleDetailResponse(
        @Schema(description = "기사 ID")
        Long id,

        @Schema(description = "기사 제목")
        String title,

        @Schema(description = "작성자. 없으면 null")
        String author,

        @Schema(description = "원문 URL")
        String originalUrl,

        @Schema(description = "카테고리. 분류 실패 시 OTHER", example = "TECH")
        String category,

        @Schema(description = "기사 발행 시각 (UTC)")
        OffsetDateTime publishedAt,

        @Schema(description = "최초 수집 시각 (UTC)")
        OffsetDateTime firstCollectedAt,

        @Schema(description = "간략 요약 슬롯 (balanced 트런케이션 ≤200자)")
        SummarySlot brief,

        @Schema(description = "균형 요약 슬롯 (수집 시 eager 생성)")
        SummarySlot balanced,

        @Schema(description = "심층 요약 슬롯 (최초 상세 조회 시 lazy 생성, 첫 요청은 PENDING일 수 있음)")
        SummarySlot deep) {}
