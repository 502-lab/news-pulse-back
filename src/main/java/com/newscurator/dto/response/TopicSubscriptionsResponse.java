package com.newscurator.dto.response;

import com.newscurator.domain.enums.NotificationTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "토픽 구독 목록 응답")
public record TopicSubscriptionsResponse(

        @Schema(description = "구독 중인 토픽 목록")
        List<NotificationTopic> topics
) {}
