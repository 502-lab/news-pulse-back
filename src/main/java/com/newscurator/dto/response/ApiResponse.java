package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 API 응답 래퍼")
public record ApiResponse<T>(
        @Schema(description = "HTTP 상태 코드", example = "200")
        int code,

        @Schema(description = "응답 상태", example = "success")
        String status,

        @Schema(description = "응답 메시지", example = "OK")
        String message,

        @Schema(description = "응답 데이터")
        T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", "OK", data);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "success", "Created", data);
    }

    public static <T> ApiResponse<T> accepted(T data) {
        return new ApiResponse<>(202, "success", "Accepted", data);
    }

    public static <T> ApiResponse<T> of(int code, String message, T data) {
        return new ApiResponse<>(code, "success", message, data);
    }
}
