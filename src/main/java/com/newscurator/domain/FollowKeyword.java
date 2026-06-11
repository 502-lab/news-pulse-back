package com.newscurator.domain;

import com.newscurator.domain.enums.KeywordType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "follow_keywords")
@Getter
@NoArgsConstructor
public class FollowKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 100)
    private String keyword;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KeywordType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public FollowKeyword(Account account, String keyword, KeywordType type) {
        this.account = account;
        this.keyword = keyword;
        this.type = type;
        this.createdAt = Instant.now();
    }
}
