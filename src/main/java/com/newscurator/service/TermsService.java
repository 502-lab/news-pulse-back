package com.newscurator.service;

import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermsService {

    private final TermsVersionRepository termsVersionRepository;

    public TermsService(TermsVersionRepository termsVersionRepository) {
        this.termsVersionRepository = termsVersionRepository;
    }

    @Transactional(readOnly = true)
    public List<TermsVersion> getActiveTerms() {
        return termsVersionRepository.findByIsActiveTrue();
    }
}
