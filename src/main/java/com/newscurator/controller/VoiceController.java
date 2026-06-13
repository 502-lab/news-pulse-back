package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.VoiceResponse;
import com.newscurator.service.VoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/voices")
@Tag(name = "Voice", description = "음성 캐릭터 목록 조회")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @GetMapping
    @Operation(
            summary = "음성 목록 조회",
            description =
                    "Naver Clova Voice로 제공 가능한 음성 캐릭터 목록을 반환합니다."
                            + " previewUrl이 null인 경우 미리듣기 샘플 미제공.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "목록 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "권한 없음")
    })
    public ResponseEntity<ApiResponse<List<VoiceResponse>>> getVoices() {
        return ResponseEntity.ok(ApiResponse.success(voiceService.findAll()));
    }
}
