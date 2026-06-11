package com.newscurator.dto.request;

import com.newscurator.domain.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "팔로우 키워드 수정 요청")
public record FollowKeywordsRequest(
        @Schema(description = "팔로우 키워드 목록") List<@NotNull KeywordEntry> keywords
) {
    @Schema(description = "키워드 항목")
    public record KeywordEntry(
            @Schema(description = "키워드", example = "삼성전자") @NotNull String keyword,
            @Schema(description = "키워드 유형", example = "COMPANY") @NotNull KeywordType type
    ) {}
}
