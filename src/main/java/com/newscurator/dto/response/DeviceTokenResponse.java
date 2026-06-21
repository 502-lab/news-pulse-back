package com.newscurator.dto.response;

import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.enums.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "FCM 디바이스 토큰 응답")
public record DeviceTokenResponse(

        @Schema(description = "토큰 ID", example = "1")
        Long id,

        @Schema(description = "계정 ID")
        UUID accountId,

        @Schema(description = "FCM 토큰")
        String token,

        @Schema(description = "디바이스 플랫폼")
        DevicePlatform platform,

        @Schema(description = "등록 시각")
        Instant createdAt,

        @Schema(description = "마지막 갱신 시각")
        Instant updatedAt
) {
    public static DeviceTokenResponse from(DeviceToken entity) {
        return new DeviceTokenResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getToken(),
                entity.getPlatform(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
