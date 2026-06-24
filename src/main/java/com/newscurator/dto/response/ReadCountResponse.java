package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 009 읽은수 응답 — 고유 기사 수(distinct article, VIEW 기준). */
@Schema(description = "내 읽은 기사 수(읽은수)")
public record ReadCountResponse(
        @Schema(description = "읽은 고유 기사 수(distinct article)", example = "42") long readCount) {}
