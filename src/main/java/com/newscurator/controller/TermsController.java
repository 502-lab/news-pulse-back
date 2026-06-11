package com.newscurator.controller;

import com.newscurator.domain.TermsVersion;
import com.newscurator.service.TermsService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terms")
public class TermsController {

    private final TermsService termsService;

    public TermsController(TermsService termsService) {
        this.termsService = termsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getActiveTerms() {
        List<TermsVersion> terms = termsService.getActiveTerms();
        List<Map<String, Object>> data = terms.stream()
                .map(tv -> Map.<String, Object>of(
                        "id", tv.getId(),
                        "type", tv.getType().name(),
                        "version", tv.getVersion(),
                        "effectiveDate", tv.getEffectiveDate(),
                        "isRequired", tv.isRequired()
                ))
                .toList();
        return ResponseEntity.ok(Map.of("terms", data));
    }
}
