package com.newscurator.dto.request;

import com.newscurator.domain.enums.TermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Schema(description = "약관 버전 생성 요청")
public record CreateTermsVersionRequest(
        @Schema(description = "약관 유형", example = "SERVICE") @NotNull TermsType type,
        @Schema(description = "버전 문자열", example = "v2.0") @NotNull String version,
        @Schema(description = "시행일", example = "2026-07-01") @NotNull LocalDate effectiveDate,
        @Schema(description = "필수 동의 여부", example = "true") boolean required
) {}
