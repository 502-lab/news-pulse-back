package com.newscurator.controller;

import com.newscurator.dto.request.OnboardingRequest;
import com.newscurator.dto.response.OnboardingStatusResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/me/onboarding")
@Tag(name = "Onboarding", description = "사용자 온보딩 — 프로필·관심사·설정 일괄 저장")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AccountRepository accountRepository;

    public OnboardingController(OnboardingService onboardingService,
                                AccountRepository accountRepository) {
        this.onboardingService = onboardingService;
        this.accountRepository = accountRepository;
    }

    @PostMapping
    @Operation(summary = "온보딩 제출",
            description = "프로필·관심사(최소 3개)·키워드·읽기방식·브리핑 설정을 일괄 저장합니다. " +
                    "관심 카테고리 3개 이상이면 personalizationActive=true로 전환됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "온보딩 저장 성공"),
            @ApiResponse(responseCode = "401", description = "미인증"),
            @ApiResponse(responseCode = "422", description = "관심 카테고리 3개 미만")
    })
    public ResponseEntity<Void> submitOnboarding(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody OnboardingRequest request) {
        accountRepository.findById(userDetails.getAccountId())
                .map(account -> {
                    onboardingService.submitOnboarding(account, request);
                    return account;
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    @Operation(summary = "온보딩 상태 조회",
            description = "온보딩 완료 여부와 개인화 활성화 상태를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<OnboardingStatusResponse> getOnboardingStatus(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(onboardingService.getStatus(account)))
                .orElse(ResponseEntity.notFound().build());
    }
}
