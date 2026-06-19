package com.newscurator.dto.request;

import com.newscurator.domain.enums.AdminTargetType;
import com.newscurator.domain.enums.NotificationTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Schema(description = "어드민 알림 발송 요청")
public record AdminNotificationRequest(
        @NotBlank @Schema(description = "알림 제목", example = "공지사항") String title,
        @NotBlank @Schema(description = "알림 내용", example = "서버 점검이 예정되어 있습니다.") String body,
        @NotNull @Schema(description = "발송 대상 타입 (ALL / ACCOUNT_IDS / TOPIC_SUBSCRIBERS)") AdminTargetType targetType,
        @Schema(description = "ACCOUNT_IDS 타입 시 발송 대상 accountId 목록 (nullable)") List<UUID> accountIds,
        @Schema(description = "TOPIC_SUBSCRIBERS 타입 시 토픽 (nullable)") NotificationTopic topic) {
}
