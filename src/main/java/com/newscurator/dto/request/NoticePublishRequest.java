package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 공지 게시 토글 요청(008 US4). */
@Schema(description = "공지 게시 토글 요청")
public record NoticePublishRequest(
        @Schema(description = "게시 여부", example = "true") @NotNull Boolean published) {}
