package com.newscurator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.Account;
import com.newscurator.domain.ConsentRecord;
import com.newscurator.domain.TermsVersion;
import com.newscurator.domain.enums.TermsType;
import com.newscurator.dto.request.ConsentInput;
import com.newscurator.dto.request.CreateTermsVersionRequest;
import com.newscurator.dto.response.ConsentRecordResponse;
import com.newscurator.dto.response.TermsContentResponse;
import com.newscurator.dto.response.TermsSection;
import com.newscurator.dto.response.TermsVersionResponse;
import com.newscurator.repository.ConsentRecordRepository;
import com.newscurator.repository.TermsVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TermsService {

    private final TermsVersionRepository termsVersionRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final ObjectMapper objectMapper;

    public TermsService(TermsVersionRepository termsVersionRepository,
                        ConsentRecordRepository consentRecordRepository,
                        ObjectMapper objectMapper) {
        this.termsVersionRepository = termsVersionRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<TermsVersion> getActiveTerms() {
        return termsVersionRepository.findByIsActiveTrue();
    }

    @Transactional(readOnly = true)
    public TermsContentResponse getTermsContent(TermsType type) {
        TermsVersion tv = termsVersionRepository.findByTypeAndIsActiveTrue(type).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "활성 약관을 찾을 수 없습니다: " + type));

        String title = null;
        String intro = null;
        List<TermsSection> sections = null;

        if (tv.getContent() != null) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        tv.getContent(), new TypeReference<>() {});
                title = (String) parsed.get("title");
                intro = (String) parsed.get("intro");
                if (parsed.get("sections") != null) {
                    sections = objectMapper.convertValue(
                            parsed.get("sections"), new TypeReference<>() {});
                }
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "약관 본문 파싱 실패");
            }
        }

        return new TermsContentResponse(
                tv.getId(), tv.getType(), tv.getVersion(),
                tv.getEffectiveDate(), tv.isRequired(), tv.isActive(),
                title, intro, sections);
    }

    @Transactional
    public TermsVersionResponse createVersion(CreateTermsVersionRequest req) {
        if (termsVersionRepository.existsByTypeAndVersion(req.type(), req.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "해당 타입·버전 조합이 이미 존재합니다: " + req.type() + " " + req.version());
        }
        termsVersionRepository.findByTypeAndIsActiveTrue(req.type())
                .forEach(tv -> {
                    tv.deactivate();
                    termsVersionRepository.save(tv);
                });

        TermsVersion newVersion = TermsVersion.builder()
                .type(req.type())
                .version(req.version())
                .effectiveDate(req.effectiveDate())
                .isRequired(req.required())
                .isActive(true)
                .build();
        termsVersionRepository.save(newVersion);

        return toResponse(newVersion);
    }

    @Transactional(readOnly = true)
    public List<ConsentRecordResponse> getConsentHistory(UUID accountId) {
        return consentRecordRepository.findByAccountId(accountId).stream()
                .map(cr -> new ConsentRecordResponse(
                        cr.getId(),
                        cr.getTermsVersion().getId(),
                        cr.getTermsVersion().getType(),
                        cr.getTermsVersion().getVersion(),
                        cr.isAgreed(),
                        cr.getAgreedAt()
                ))
                .toList();
    }

    @Transactional
    public void submitConsents(Account account, List<ConsentInput> consents) {
        for (ConsentInput ci : consents) {
            UUID tvId = ci.termsVersionId();
            if (consentRecordRepository.findByAccountIdAndTermsVersionId(account.getId(), tvId).isPresent()) {
                continue;
            }
            termsVersionRepository.findById(tvId).ifPresent(tv -> {
                ConsentRecord cr = ConsentRecord.builder()
                        .account(account)
                        .termsVersion(tv)
                        .agreed(ci.agreed())
                        .build();
                consentRecordRepository.save(cr);
            });
        }
    }

    private TermsVersionResponse toResponse(TermsVersion tv) {
        return new TermsVersionResponse(
                tv.getId(), tv.getType(), tv.getVersion(),
                tv.getEffectiveDate(), tv.isRequired(), tv.isActive()
        );
    }
}
