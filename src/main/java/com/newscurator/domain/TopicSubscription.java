package com.newscurator.domain;

import com.newscurator.domain.enums.NotificationTopic;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "topic_subscriptions")
@Getter
@NoArgsConstructor
public class TopicSubscription {

    @EmbeddedId
    private TopicSubscriptionId id;

    @Column(name = "subscribed_at", nullable = false, updatable = false)
    private Instant subscribedAt;

    public TopicSubscription(UUID accountId, NotificationTopic topic) {
        this.id = new TopicSubscriptionId(accountId, topic);
        this.subscribedAt = Instant.now();
    }

    public UUID getAccountId() {
        return id.getAccountId();
    }

    public NotificationTopic getTopic() {
        return id.getTopic();
    }
}
