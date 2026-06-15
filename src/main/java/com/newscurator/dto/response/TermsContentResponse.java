package com.newscurator.dto.response;

import com.newscurator.domain.enums.TermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "약관 본문 응답")
public record TermsContentResponse(
        @Schema(description = "약관 버전 ID")
        UUID id,

        @Schema(description = "약관 유형", example = "SERVICE")
        TermsType type,

        @Schema(description = "버전", example = "1.0")
        String version,

        @Schema(description = "시행일", example = "2026-06-01")
        LocalDate effectiveDate,

        @Schema(description = "필수 동의 여부")
        boolean isRequired,

        @Schema(description = "활성 여부")
        boolean isActive,

        @Schema(description = "약관 제목. MARKETING 등 본문 없는 유형은 null", nullable = true)
        String title,

        @Schema(description = "약관 소개 문구. 본문 없는 유형은 null", nullable = true)
        String intro,

        @Schema(description = "약관 섹션 목록. 본문 없는 유형은 null", nullable = true)
        List<TermsSection> sections
) {}
