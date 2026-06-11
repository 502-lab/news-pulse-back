package com.newscurator.dto.response;

import com.newscurator.domain.enums.TermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "동의 기록 응답")
public record ConsentRecordResponse(
        @Schema(description = "동의 기록 ID") UUID id,
        @Schema(description = "약관 버전 ID") UUID termsVersionId,
        @Schema(description = "약관 유형") TermsType termsType,
        @Schema(description = "버전") String version,
        @Schema(description = "동의 여부") boolean agreed,
        @Schema(description = "동의 시각") Instant agreedAt
) {}
