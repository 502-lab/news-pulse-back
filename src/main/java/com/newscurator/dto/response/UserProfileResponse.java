package com.newscurator.dto.response;

import com.newscurator.domain.enums.AgeGroup;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 응답")
public record UserProfileResponse(
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "연령대") AgeGroup ageGroup,
        @Schema(description = "직업") String occupation
) {}
