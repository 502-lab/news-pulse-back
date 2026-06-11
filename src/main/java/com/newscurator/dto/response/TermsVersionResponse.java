package com.newscurator.dto.response;

import com.newscurator.domain.enums.TermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "약관 버전 응답")
public record TermsVersionResponse(
        @Schema(description = "약관 버전 ID") UUID id,
        @Schema(description = "약관 유형") TermsType type,
        @Schema(description = "버전") String version,
        @Schema(description = "시행일") LocalDate effectiveDate,
        @Schema(description = "필수 여부") boolean required,
        @Schema(description = "활성 여부") boolean active
) {}
