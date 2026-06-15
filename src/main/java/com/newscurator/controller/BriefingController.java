package com.newscurator.controller;

import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.BriefingResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.BriefingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/briefing")
@Tag(name = "Briefing", description = "데일리 브리핑 TTS — 오늘의 뉴스 N건 음성 재생 큐")
public class BriefingController {

    private final BriefingService briefingService;
    private final AccountRepository accountRepository;

    public BriefingController(BriefingService briefingService, AccountRepository accountRepository) {
        this.briefingService = briefingService;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/today")
    @Operation(
            summary = "오늘의 브리핑 조회",
            description = "당일 브리핑 TTS 큐를 반환합니다. "
                    + "캐시 히트이면 저장된 기사 목록으로 현재 TTS 상태를 재조회합니다(stale 없음). "
                    + "캐시 미스이면 개인화 피드 상위 N건(summaryStatus=COMPLETED)으로 신규 생성합니다. "
                    + "모든 항목이 READY이면 200, 처리 중 항목이 있으면 202를 반환합니다. "
                    + "COMPLETED 기사가 없으면 404를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "전체 READY — 즉시 재생 가능"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "일부 TTS 처리 중 — 폴링 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미인증"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "이메일 미인증"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "오늘 브리핑 가능한 완료 기사 없음")
    })
    public ResponseEntity<ApiResponse<BriefingResponse>> getTodayBrief(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            throw new BadCredentialsException("Authentication required");
        }
        var account = accountRepository.findById(userDetails.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        BriefingResponse response = briefingService.getOrCreateTodayBrief(account);

        boolean allReady = response.ttsItems().stream()
                .allMatch(item -> item.status() == TtsStatus.READY);

        return ResponseEntity
                .status(allReady ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(allReady ? ApiResponse.success(response) : ApiResponse.accepted(response));
    }
}
