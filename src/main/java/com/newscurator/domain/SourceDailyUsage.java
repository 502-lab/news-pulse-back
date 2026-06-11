package com.newscurator.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "source_daily_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceDailyUsage {

    @EmbeddedId
    private SourceDailyUsageId id;

    @Column(name = "call_count", nullable = false)
    private int callCount = 0;

    @Builder
    public SourceDailyUsage(Long sourceId, LocalDate usageDate) {
        this.id = new SourceDailyUsageId(sourceId, usageDate);
    }

    public void increment() {
        this.callCount++;
    }

    public boolean isOverBudget(int budget) {
        return this.callCount >= budget;
    }
}
