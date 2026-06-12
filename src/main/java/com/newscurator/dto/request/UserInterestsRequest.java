package com.newscurator.dto.request;

import com.newscurator.validation.ValidCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "관심사 수정 요청 (최소 3개)")
public record UserInterestsRequest(
        @Schema(description = "관심 카테고리 목록 (최소 3개). 허용값: ECONOMY_FINANCE, SCIENCE, POLITICS, SPORTS, WORLD, ENTERTAINMENT_CULTURE, HEALTH_MEDICINE, AUTOMOTIVE, IT, OTHER", example = "[\"IT\",\"ECONOMY_FINANCE\",\"SCIENCE\"]")
        @NotNull
        @Size(min = 3, message = "관심 카테고리는 최소 3개 이상이어야 합니다")
        @ValidCategory
        List<String> categories
) {}
