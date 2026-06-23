package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 공지 생성 요청(008 US4). */
@Schema(description = "공지 생성 요청")
public record NoticeCreateRequest(
        @Schema(description = "제목(≤200)") @NotBlank @Size(max = 200) String title,
        @Schema(description = "본문") @NotBlank String content,
        @Schema(description = "즉시 게시 여부", example = "false") boolean published) {}
