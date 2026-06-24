package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.ReadCountResponse;
import com.newscurator.dto.response.ReadHistoryListResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.ReadHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 009 읽기 추적 조회 API(US2) — 본인 읽은수·조회 이력. 인증 본인 스코프. */
@Tag(name = "Read Tracking", description = "내 읽은수·조회 이력 API (009)")
@RestController
public class ReadHistoryController {

    private final ReadHistoryService readHistoryService;

    public ReadHistoryController(ReadHistoryService readHistoryService) {
        this.readHistoryService = readHistoryService;
    }

    @Operation(
            summary = "내 읽은 기사 수 조회",
            description = "인증 사용자가 읽은 고유 기사 수(읽은수, distinct article)를 반환합니다. 본인 데이터만.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping("/api/v1/me/read-count")
    public ResponseEntity<ApiResponse<ReadCountResponse>> readCount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                ApiResponse.success(readHistoryService.getReadCount(userDetails.getAccountId())));
    }

    @Operation(
            summary = "내 조회 이력 조회",
            description =
                    "인증 사용자의 조회 이력을 최신순으로 반환합니다. 같은 기사를 여러 번 조회해도 이력엔 1건"
                            + "(article 기준 최신 1건)만 노출됩니다. 커서 페이지네이션. 본인 데이터만.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping("/api/v1/me/read-history")
    public ResponseEntity<ApiResponse<ReadHistoryListResponse>> readHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "이전 응답의 nextCursor 값(최초 호출 시 생략)")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "페이지 크기 (1~50, 기본 20)")
                    @Min(1)
                    @Max(50)
                    @RequestParam(required = false, defaultValue = "20")
                    int size) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        readHistoryService.getHistory(userDetails.getAccountId(), cursor, size)));
    }
}
