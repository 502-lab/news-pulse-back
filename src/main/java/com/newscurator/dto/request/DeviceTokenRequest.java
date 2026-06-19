package com.newscurator.dto.request;

import com.newscurator.domain.enums.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "FCM 디바이스 토큰 등록 요청")
public record DeviceTokenRequest(

        @Schema(description = "FCM 디바이스 토큰", example = "fcm-token-abc123")
        @NotBlank
        String token,

        @Schema(description = "디바이스 플랫폼 (IOS, ANDROID, WEB)", example = "IOS")
        @NotNull
        DevicePlatform platform
) {}
