package com.newscurator.domain;

import com.newscurator.domain.enums.NotificationTopic;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TopicSubscriptionId implements Serializable {

    private UUID accountId;

    @Enumerated(EnumType.STRING)
    private NotificationTopic topic;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopicSubscriptionId other)) return false;
        return Objects.equals(accountId, other.accountId) && Objects.equals(topic, other.topic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, topic);
    }
}
