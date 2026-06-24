package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 제외 키워드 등록 요청(008 FR-032). */
@Schema(description = "제외 키워드 등록 요청")
public record ExcludedKeywordRequest(
        @Schema(description = "배제할 키워드", example = "광고") @NotBlank @Size(max = 100) String keyword) {}
