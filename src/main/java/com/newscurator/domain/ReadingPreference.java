package com.newscurator.domain;

import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.SummaryDepth;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reading_preferences")
@Getter
@NoArgsConstructor
public class ReadingPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_depth", nullable = false, length = 20)
    private SummaryDepth summaryDepth = SummaryDepth.BALANCED;

    @Enumerated(EnumType.STRING)
    @Column(name = "consume_mode", nullable = false, length = 20)
    private ConsumeMode consumeMode = ConsumeMode.READ;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public ReadingPreference(Account account, SummaryDepth summaryDepth, ConsumeMode consumeMode) {
        this.account = account;
        this.summaryDepth = summaryDepth != null ? summaryDepth : SummaryDepth.BALANCED;
        this.consumeMode = consumeMode != null ? consumeMode : ConsumeMode.READ;
        this.updatedAt = Instant.now();
    }

    public void update(SummaryDepth summaryDepth, ConsumeMode consumeMode) {
        this.summaryDepth = summaryDepth;
        this.consumeMode = consumeMode;
        this.updatedAt = Instant.now();
    }
}
