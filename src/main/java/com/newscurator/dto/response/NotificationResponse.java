package com.newscurator.dto.response;

import com.newscurator.domain.Notification;
import com.newscurator.domain.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "인앱 알림 응답")
public record NotificationResponse(

        @Schema(description = "알림 ID", example = "1")
        Long id,

        @Schema(description = "알림 타입. BREAKING=속보, BRIEFING=AI브리핑, TTS_READY=TTS완료, SYSTEM=시스템", example = "BREAKING")
        NotificationType type,

        @Schema(description = "알림 제목", example = "속보: 주요 뉴스")
        String title,

        @Schema(description = "알림 본문. null 가능", example = "주요 기사 내용 요약")
        String body,

        @Schema(description = "참조 ID (기사 ID, TTS ID 등). null 가능", example = "article-123")
        String referenceId,

        @Schema(description = "읽음 여부", example = "false")
        boolean isRead,

        @Schema(description = "생성 시각 (UTC)", example = "2026-06-18T00:00:00Z")
        Instant createdAt,

        @Schema(description = "만료 시각 (생성 후 90일, UTC)", example = "2026-09-16T00:00:00Z")
        Instant expiresAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getReferenceId(),
                n.isRead(),
                n.getCreatedAt(),
                n.getExpiresAt()
        );
    }
}
