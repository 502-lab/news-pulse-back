package com.newscurator.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        SummarySlot deep,

        // SC-002: 글로벌 Jackson inclusion 설정과 무관하게 biasScore 필드를 항상 직렬화(omit 금지)
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "편향 분석 점수. 필드는 항상 포함되며, BiasAnalysis 행이 없으면 null, "
                + "분석 미완료/실패면 value=null + status로 전달 (SC-002)")
        BiasScoreResponse biasScore) {}
