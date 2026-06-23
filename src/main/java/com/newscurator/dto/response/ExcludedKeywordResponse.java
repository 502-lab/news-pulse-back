package com.newscurator.dto.response;

import com.newscurator.domain.ExcludedKeyword;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 제외 키워드 응답(008 FR-032). */
@Schema(description = "제외 키워드")
public record ExcludedKeywordResponse(
        @Schema(description = "식별자") long id,
        @Schema(description = "키워드") String keyword,
        @Schema(description = "등록 시각") Instant createdAt) {

    public static ExcludedKeywordResponse from(ExcludedKeyword k) {
        return new ExcludedKeywordResponse(k.getId(), k.getKeyword(), k.getCreatedAt());
    }
}
