package com.newscurator.dto.request;

import com.newscurator.domain.enums.AgeGroup;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 수정 요청")
public record UserProfileRequest(
        @Schema(description = "닉네임 (최대 50자)", example = "홍길동") String nickname,
        @Schema(description = "연령대", example = "THIRTIES") AgeGroup ageGroup,
        @Schema(description = "직업", example = "개발자") String occupation
) {}
