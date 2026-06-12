package com.newscurator.dto.request;

import com.newscurator.domain.enums.AgeGroup;
import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.KeywordType;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.validation.ValidCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "온보딩 일괄 저장 요청")
public record OnboardingRequest(

        @Schema(description = "닉네임 (최대 50자)", example = "홍길동")
        String nickname,

        @Schema(description = "연령대", example = "THIRTIES")
        AgeGroup ageGroup,

        @Schema(description = "직업", example = "개발자")
        String occupation,

        @Schema(description = "관심 카테고리 목록 (최소 3개 필수). 허용값: ECONOMY_FINANCE, SCIENCE, POLITICS, SPORTS, WORLD, ENTERTAINMENT_CULTURE, HEALTH_MEDICINE, AUTOMOTIVE, IT, OTHER", example = "[\"IT\",\"ECONOMY_FINANCE\",\"SCIENCE\"]")
        @NotNull
        @Size(min = 3, message = "관심 카테고리는 최소 3개 이상이어야 합니다")
        @ValidCategory
        List<String> categories,

        @Schema(description = "팔로우 키워드 목록")
        List<@Valid KeywordEntry> keywords,

        @Schema(description = "요약 깊이", example = "BALANCED")
        SummaryDepth summaryDepth,

        @Schema(description = "소비 방식", example = "READ")
        ConsumeMode consumeMode,

        @Schema(description = "브리핑 시각 (HH:mm)", example = "08:00")
        LocalTime briefingTime,

        @Schema(description = "타임존 오프셋(분)", example = "540")
        Short timezoneOffset,

        @Schema(description = "음성 브리핑 활성화", example = "false")
        boolean voiceEnabled,

        @Schema(description = "푸시 알림 동의", example = "false")
        boolean pushAgreed
) {
    @Schema(description = "팔로우 키워드 항목")
    public record KeywordEntry(
            @Schema(description = "키워드", example = "삼성전자")
            @NotNull String keyword,
            @Schema(description = "키워드 유형", example = "COMPANY")
            @NotNull KeywordType type
    ) {}
}
