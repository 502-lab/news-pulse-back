package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 어드민 캠페인 푸시 요청(008 FR-042). campaignId는 서버 생성(매 발송 고유). */
@Schema(description = "어드민 캠페인 푸시 요청")
public record AdminPushRequest(
        @Schema(description = "제목") @NotBlank String title,
        @Schema(description = "본문") @NotBlank String body) {}
