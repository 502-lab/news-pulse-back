package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor
public class NotificationPreferences {

    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    private UUID accountId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "rising_enabled", nullable = false)
    private boolean risingEnabled = true;

    @Column(name = "bias_enabled", nullable = false)
    private boolean biasEnabled = true;

    public NotificationPreferences(UUID accountId) {
        this.accountId = accountId;
    }

    public void update(boolean pushEnabled, boolean emailEnabled, boolean risingEnabled, boolean biasEnabled) {
        this.pushEnabled = pushEnabled;
        this.emailEnabled = emailEnabled;
        this.risingEnabled = risingEnabled;
        this.biasEnabled = biasEnabled;
    }
}
