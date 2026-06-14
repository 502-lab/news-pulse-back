package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 API 응답 래퍼")
public record ApiResponse<T>(
        @Schema(description = "응답 상태", example = "success")
        String status,

        @Schema(description = "응답 데이터")
        T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", data);
    }
}
