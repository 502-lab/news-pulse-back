package com.newscurator.dto.request;

import com.newscurator.domain.enums.NotificationTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "토픽 구독 교체 요청 (replace-all)")
public record TopicSubscriptionsRequest(

        @Schema(description = "구독할 토픽 목록 (BREAKING, BRIEFING, TTS_READY)", example = "[\"BREAKING\", \"BRIEFING\"]")
        @NotNull
        List<NotificationTopic> topics
) {}
