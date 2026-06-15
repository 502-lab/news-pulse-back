package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "약관 섹션")
public record TermsSection(
        @Schema(description = "섹션 제목", example = "제1조 (목적)")
        String heading,

        @Schema(description = "섹션 내용 단락 목록")
        List<String> paragraphs
) {}
