package com.newscurator.controller;

import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.request.TtsRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.service.TtsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "TTS", description = "기사 TTS 생성 및 상태 조회")
@RestController
@RequestMapping("/api/v1/articles/{articleId}/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @Operation(
            summary = "기사 TTS 요청",
            description = "기사 요약을 지정 음성으로 TTS 변환 요청. 이미 READY이면 200, 처리 중이거나 새 요청이면 202."
                    + " FAILED 상태는 자동으로 PENDING으로 리셋 후 재처리. 멱등 — 중복 요청 안전.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이미 READY 상태"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "PENDING/PROCESSING 처리 대기 중"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "기사 요약 미완료 (SUMMARY_NOT_READY)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "유효하지 않은 voiceId")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<TtsStatusResponse>> requestTts(
            @Parameter(description = "기사 ID") @PathVariable Long articleId,
            @Valid @RequestBody TtsRequest request) {
        TtsStatusResponse response = ttsService.requestArticleTts(articleId, request.voiceId());
        HttpStatus status = response.status() == TtsStatus.READY ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }

    @Operation(
            summary = "기사 TTS 상태 조회",
            description = "지정 기사와 음성의 TTS 처리 상태를 조회한다. READY이면 audioUrl 포함.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "TTS 없음")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<TtsStatusResponse>> getTtsStatus(
            @Parameter(description = "기사 ID") @PathVariable Long articleId,
            @Parameter(description = "Naver Clova Voice speaker ID", required = true)
            @RequestParam String voiceId) {
        return ResponseEntity.ok(ApiResponse.success(ttsService.getArticleTtsStatus(articleId, voiceId)));
    }
}
