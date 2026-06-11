package com.newscurator.dto.response;

import com.newscurator.domain.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "팔로우 키워드 응답")
public record FollowKeywordsResponse(
        @Schema(description = "팔로우 키워드 목록") List<KeywordEntry> keywords
) {
    @Schema(description = "키워드 항목")
    public record KeywordEntry(
            @Schema(description = "키워드") String keyword,
            @Schema(description = "키워드 유형") KeywordType type
    ) {}
}
