package com.newscurator.domain;

import com.newscurator.domain.enums.SocialProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "social_connections")
@Getter
@NoArgsConstructor
public class SocialConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    /** Apple 최초 로그인 시 수신한 userInfo JSON (이후 로그인은 null). */
    @Column(name = "user_info", columnDefinition = "TEXT")
    private String userInfo;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    @Builder
    public SocialConnection(Account account, SocialProvider provider,
                            String providerUserId, String providerEmail, String userInfo) {
        this.account = account;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.providerEmail = providerEmail;
        this.userInfo = userInfo;
    }

    @PrePersist
    void prePersist() {
        this.connectedAt = Instant.now();
    }
}
