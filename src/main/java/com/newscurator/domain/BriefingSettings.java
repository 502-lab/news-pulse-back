package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "briefing_settings")
@Getter
@NoArgsConstructor
public class BriefingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @Column(name = "briefing_time", nullable = false)
    private LocalTime briefingTime = LocalTime.of(8, 0);

    @Column(name = "timezone_offset", nullable = false)
    private Short timezoneOffset = 540;

    @Column(name = "voice_enabled", nullable = false)
    private boolean voiceEnabled = false;

    @Column(name = "push_agreed", nullable = false)
    private boolean pushAgreed = false;

    @Column(name = "push_agreed_at")
    private Instant pushAgreedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public BriefingSettings(Account account, LocalTime briefingTime, Short timezoneOffset,
                             boolean voiceEnabled, boolean pushAgreed) {
        this.account = account;
        this.briefingTime = briefingTime != null ? briefingTime : LocalTime.of(8, 0);
        this.timezoneOffset = timezoneOffset != null ? timezoneOffset : 540;
        this.voiceEnabled = voiceEnabled;
        this.pushAgreed = pushAgreed;
        if (pushAgreed) {
            this.pushAgreedAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    public void update(LocalTime briefingTime, Short timezoneOffset,
                       boolean voiceEnabled, boolean pushAgreed) {
        this.briefingTime = briefingTime;
        this.timezoneOffset = timezoneOffset;
        this.voiceEnabled = voiceEnabled;
        if (pushAgreed && !this.pushAgreed) {
            this.pushAgreedAt = Instant.now();
        } else if (!pushAgreed) {
            this.pushAgreedAt = null;
        }
        this.pushAgreed = pushAgreed;
        this.updatedAt = Instant.now();
    }
}
