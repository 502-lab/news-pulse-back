package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "consent_records")
@Getter
@NoArgsConstructor
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_version_id", nullable = false)
    private TermsVersion termsVersion;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    @Builder
    public ConsentRecord(Account account, TermsVersion termsVersion, boolean agreed) {
        this.account = account;
        this.termsVersion = termsVersion;
        this.agreed = agreed;
        this.agreedAt = Instant.now();
    }
}
