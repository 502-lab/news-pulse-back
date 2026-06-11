package com.newscurator.repository;

import com.newscurator.domain.TermsVersion;
import com.newscurator.domain.enums.TermsType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsVersionRepository extends JpaRepository<TermsVersion, UUID> {
    List<TermsVersion> findByIsActiveTrue();
    List<TermsVersion> findByTypeAndIsActiveTrue(TermsType type);
    List<TermsVersion> findByIsActiveTrueAndIsRequiredTrue();
    boolean existsByTypeAndVersion(TermsType type, String version);
    List<TermsVersion> findByType(TermsType type);
}
