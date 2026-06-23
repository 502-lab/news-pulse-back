package com.newscurator.dto.request;

import com.newscurator.domain.enums.AccountRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 역할 변경 요청(008 US1). USER↔ADMIN.
 */
@Schema(description = "사용자 역할 변경 요청")
public record RoleChangeRequest(
        @Schema(description = "변경할 역할", example = "ADMIN") @NotNull AccountRole role) {}
