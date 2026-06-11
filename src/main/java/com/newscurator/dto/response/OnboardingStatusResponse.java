package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "온보딩 상태 응답")
public record OnboardingStatusResponse(
        @Schema(description = "온보딩 완료 여부") boolean onboardingCompleted,
        @Schema(description = "개인화 활성화 여부") boolean personalizationActive
) {}
