package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SourceDailyUsageId implements Serializable {

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SourceDailyUsageId that)) return false;
        return Objects.equals(sourceId, that.sourceId) && Objects.equals(usageDate, that.usageDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, usageDate);
    }
}
