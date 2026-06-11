package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "관심사 응답")
public record UserInterestsResponse(
        @Schema(description = "관심 카테고리 목록") List<String> categories
) {}
