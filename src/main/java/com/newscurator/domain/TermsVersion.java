package com.newscurator.domain;

import com.newscurator.domain.enums.TermsType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "terms_versions")
@Getter
@NoArgsConstructor
public class TermsVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TermsType type;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired = true;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Builder
    public TermsVersion(TermsType type, String version, LocalDate effectiveDate,
                        boolean isRequired, boolean isActive) {
        this.type = type;
        this.version = version;
        this.effectiveDate = effectiveDate;
        this.isRequired = isRequired;
        this.isActive = isActive;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void deactivate() {
        this.isActive = false;
    }
}
