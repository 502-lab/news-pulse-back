package com.newscurator.dto.response;

import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.enums.EmailSubscriptionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "이메일 구독 상태 응답")
public record EmailSubscriptionResponse(
        @Schema(description = "계정 UUID") UUID accountId,
        @Schema(description = "구독 타입", example = "WEEKLY_EMAIL") EmailSubscriptionType type,
        @Schema(description = "구독 활성 여부", example = "true") boolean active,
        @Schema(description = "구독 시작 시각") Instant subscribedAt) {

    public static EmailSubscriptionResponse from(EmailSubscription sub) {
        return new EmailSubscriptionResponse(
                sub.getAccountId(),
                sub.getType(),
                sub.isActive(),
                sub.getSubscribedAt());
    }
}
