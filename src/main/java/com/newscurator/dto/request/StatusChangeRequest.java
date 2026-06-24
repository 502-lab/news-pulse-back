package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 활성/비활성 변경 요청(008 US1). active=true → 활성(ACTIVE), false → 비활성(SUSPENDED, 로그인 차단).
 */
@Schema(description = "사용자 활성/비활성 변경 요청")
public record StatusChangeRequest(
        @Schema(description = "활성 여부(true=활성, false=비활성)", example = "false") @NotNull
                Boolean active) {}
