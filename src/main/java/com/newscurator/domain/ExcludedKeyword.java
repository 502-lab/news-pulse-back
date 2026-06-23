package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집/집계 배제 키워드(008 FR-032). 등록된 키워드는 트렌드 집계에서 배제된다.
 */
@Entity
@Table(name = "excluded_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExcludedKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String keyword;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public ExcludedKeyword(String keyword, UUID createdBy) {
        this.keyword = keyword;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }
}
