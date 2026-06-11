package com.newscurator.dto.request;

import com.newscurator.domain.enums.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "뉴스 피드 목록 요청")
public record FeedRequest(
        @Schema(description = "페이지네이션 커서 토큰. 첫 페이지 요청 시 생략")
        String cursor,

        @Positive
        @Schema(description = "페이지 크기. 기본값 20, 최대 100 (초과 시 100으로 clamp)", example = "20")
        Integer size,

        @Schema(description = "카테고리 필터. 생략 시 전체 조회")
        Category category) {}
