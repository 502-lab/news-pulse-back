package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** (시간슬롯 × 카테고리 × term) 키워드 집계. 멱등 UPSERT(PK). */
@Entity
@Table(name = "trend_keyword_slot")
@IdClass(TrendKeywordSlot.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendKeywordSlot {

    @Id
    @Column(name = "slot_start")
    private Instant slotStart;

    @Id
    @Column(name = "category", length = 32)
    private String category;

    @Id
    @Column(name = "term", length = 64)
    private String term;

    @Column(name = "article_count", nullable = false)
    private int articleCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 복합키 */
    public static class Pk implements Serializable {
        private Instant slotStart;
        private String category;
        private String term;

        public Pk() {}

        public Pk(Instant slotStart, String category, String term) {
            this.slotStart = slotStart;
            this.category = category;
            this.term = term;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(slotStart, pk.slotStart)
                    && Objects.equals(category, pk.category)
                    && Objects.equals(term, pk.term);
        }

        @Override
        public int hashCode() {
            return Objects.hash(slotStart, category, term);
        }
    }
}
