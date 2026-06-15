package com.newscurator.controller;

import com.newscurator.domain.TermsVersion;
import com.newscurator.dto.request.ConsentInput;
import com.newscurator.dto.request.CreateTermsVersionRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ConsentRecordResponse;
import com.newscurator.dto.response.TermsVersionResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.TermsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "Terms", description = "약관 버전 관리 및 동의 이력")
public class TermsController {

    private final TermsService termsService;
    private final AccountRepository accountRepository;

    public TermsController(TermsService termsService, AccountRepository accountRepository) {
        this.termsService = termsService;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/api/v1/terms")
    @Operation(summary = "활성 약관 목록 조회 (public)",
            description = "인증 없이 조회 가능한 활성 약관 버전 목록입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveTerms() {
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
        return ResponseEntity.ok(ApiResponse.success(Map.of("terms", data)));
    }

    @PostMapping("/api/v1/admin/terms")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "새 약관 버전 등록 (ADMIN 전용)",
            description = "동일 type+version 조합은 409를 반환합니다. 같은 타입의 기존 활성 버전은 비활성 처리됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 존재하는 버전")
    })
    public ResponseEntity<ApiResponse<TermsVersionResponse>> createTermsVersion(
            @Valid @RequestBody CreateTermsVersionRequest request) {
        TermsVersionResponse response = termsService.createVersion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    @GetMapping("/api/v1/me/consents")
    @Operation(summary = "내 동의 이력 조회",
            description = "인증된 사용자의 모든 약관 동의 기록을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<ApiResponse<List<ConsentRecordResponse>>> getConsentHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(
                termsService.getConsentHistory(userDetails.getAccountId())));
    }

    @PostMapping("/api/v1/me/consents")
    @Operation(summary = "약관 재동의 제출 (멱등)",
            description = "이미 동의한 버전은 무시합니다(멱등). 새로운 버전에 대한 동의만 저장됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "동의 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<ApiResponse<Void>> submitConsents(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody List<@Valid ConsentInput> consents) {
        accountRepository.findById(userDetails.getAccountId())
                .map(account -> {
                    termsService.submitConsents(account, consents);
                    return account;
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
