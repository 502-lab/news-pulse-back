package com.newscurator.domain;

import com.newscurator.domain.enums.AgeGroup;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @Column(length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_group", length = 20)
    private AgeGroup ageGroup;

    @Column(length = 100)
    private String occupation;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public UserProfile(Account account, String nickname, AgeGroup ageGroup, String occupation) {
        this.account = account;
        this.nickname = nickname;
        this.ageGroup = ageGroup;
        this.occupation = occupation;
        this.updatedAt = Instant.now();
    }

    public void update(String nickname, AgeGroup ageGroup, String occupation) {
        this.nickname = nickname;
        this.ageGroup = ageGroup;
        this.occupation = occupation;
        this.updatedAt = Instant.now();
    }
}
