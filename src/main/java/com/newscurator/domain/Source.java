package com.newscurator.domain;

import com.newscurator.domain.enums.SourceAdapterType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "feed_url", nullable = false, unique = true, length = 1024)
    private String feedUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 32)
    private SourceAdapterType adapterType;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "collection_interval_minutes")
    private Integer collectionIntervalMinutes;

    @Column(name = "call_budget_daily", nullable = false)
    private int callBudgetDaily = 1000;

    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount = 0;

    @Column(name = "last_collected_at")
    private OffsetDateTime lastCollectedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public Source(
            String name,
            String feedUrl,
            SourceAdapterType adapterType,
            boolean active,
            Integer collectionIntervalMinutes,
            int callBudgetDaily) {
        this.name = name;
        this.feedUrl = feedUrl;
        this.adapterType = adapterType;
        this.active = active;
        this.collectionIntervalMinutes = collectionIntervalMinutes;
        this.callBudgetDaily = callBudgetDaily;
    }

    public void recordCollected() {
        this.lastCollectedAt = OffsetDateTime.now();
        this.consecutiveFailureCount = 0;
    }

    public void recordFailure() {
        this.consecutiveFailureCount++;
    }
}
