package com.newscurator.domain;

import com.newscurator.domain.enums.EmailSubscriptionType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_subscriptions")
@Getter
@NoArgsConstructor
public class EmailSubscription {

    @EmbeddedId
    private EmailSubscriptionId id;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "subscribed_at", nullable = false, updatable = false)
    private Instant subscribedAt;

    public static EmailSubscription create(UUID accountId, EmailSubscriptionType type) {
        EmailSubscription sub = new EmailSubscription();
        sub.id = new EmailSubscriptionId(accountId, type);
        sub.active = true;
        sub.subscribedAt = Instant.now();
        return sub;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getAccountId() {
        return id.getAccountId();
    }

    public EmailSubscriptionType getType() {
        return id.getType();
    }
}
