package com.newscurator.controller;

import com.newscurator.dto.request.*;
import com.newscurator.dto.response.*;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.AuthService;
import com.newscurator.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "인증된 사용자 본인 정보 조회·수정")
public class MeController {

    private final AuthService authService;
    private final AccountRepository accountRepository;
    private final ProfileService profileService;

    public MeController(AuthService authService, AccountRepository accountRepository,
                        ProfileService profileService) {
        this.authService = authService;
        this.accountRepository = accountRepository;
        this.profileService = profileService;
    }

    @GetMapping
    @Operation(summary = "내 계정 정보 조회", description = "인증 토큰 기준 계정 요약 정보를 반환합니다. requiresReConsent 포함.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<AccountSummaryResponse> getMe(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(authService.buildAccountSummary(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Profile ───

    @GetMapping("/profile")
    @Operation(summary = "프로필 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.getProfile(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/profile")
    @Operation(summary = "프로필 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UserProfileRequest request) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.updateProfile(account, request)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Interests ───

    @GetMapping("/interests")
    @Operation(summary = "관심사 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<UserInterestsResponse> getInterests(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.getInterests(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/interests")
    @Operation(summary = "관심사 수정 (최소 3개)", description = "3개 미만 제출 시 422를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "미인증"),
            @ApiResponse(responseCode = "422", description = "카테고리 3개 미만")
    })
    public ResponseEntity<UserInterestsResponse> updateInterests(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UserInterestsRequest request) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.updateInterests(account, request)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Keywords ───

    @GetMapping("/keywords")
    @Operation(summary = "팔로우 키워드 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<FollowKeywordsResponse> getKeywords(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.getKeywords(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/keywords")
    @Operation(summary = "팔로우 키워드 수정 (전체 교체)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<FollowKeywordsResponse> updateKeywords(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody FollowKeywordsRequest request) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.updateKeywords(account, request)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Reading Preference ───

    @GetMapping("/reading-preference")
    @Operation(summary = "읽기 방식 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<ReadingPreferenceResponse> getReadingPreference(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.getReadingPreference(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/reading-preference")
    @Operation(summary = "읽기 방식 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<ReadingPreferenceResponse> updateReadingPreference(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ReadingPreferenceRequest request) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.updateReadingPreference(account, request)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Briefing Settings ───

    @GetMapping("/briefing-settings")
    @Operation(summary = "브리핑 설정 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<BriefingSettingsResponse> getBriefingSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.getBriefingSettings(account)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/briefing-settings")
    @Operation(summary = "브리핑 설정 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "미인증")
    })
    public ResponseEntity<BriefingSettingsResponse> updateBriefingSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody BriefingSettingsRequest request) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(profileService.updateBriefingSettings(account, request)))
                .orElse(ResponseEntity.notFound().build());
    }
}
