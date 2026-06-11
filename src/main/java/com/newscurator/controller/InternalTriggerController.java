package com.newscurator.controller;

import com.newscurator.service.AiProcessingService;
import com.newscurator.service.CollectionService;
import com.newscurator.service.ExpiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** local 프로파일 전용 수동 트리거 엔드포인트. 운영 환경에서는 로드되지 않음. */
@Tag(name = "Internal Trigger (Local Only)", description = "로컬 개발 전용 수동 트리거. local 프로파일에서만 활성화됨")
@RestController
@RequestMapping("/internal/trigger")
@Profile("local")
public class InternalTriggerController {

    private final CollectionService collectionService;
    private final AiProcessingService aiProcessingService;
    private final ExpiryService expiryService;

    public InternalTriggerController(
            CollectionService collectionService,
            AiProcessingService aiProcessingService,
            ExpiryService expiryService) {
        this.collectionService = collectionService;
        this.aiProcessingService = aiProcessingService;
        this.expiryService = expiryService;
    }

    @Operation(summary = "뉴스 수집 수동 트리거", description = "등록된 모든 출처(RSS/Naver)에서 즉시 뉴스를 수집합니다.")
    @ApiResponse(responseCode = "200", description = "트리거 완료")
    @PostMapping("/collect")
    public ResponseEntity<String> triggerCollection() {
        collectionService.collectAll();
        return ResponseEntity.ok("collection triggered");
    }

    @Operation(summary = "AI 처리 수동 트리거", description = "PENDING 상태 기사를 즉시 AI 분류·요약 처리합니다.")
    @ApiResponse(responseCode = "200", description = "트리거 완료")
    @PostMapping("/ai-process")
    public ResponseEntity<String> triggerAiProcessing() {
        aiProcessingService.processBatch();
        return ResponseEntity.ok("ai processing triggered");
    }

    @Operation(summary = "만료 기사 정리 수동 트리거", description = "보존 기간 초과 기사를 숨기고, 유예 기간까지 경과한 기사를 삭제합니다.")
    @ApiResponse(responseCode = "200", description = "트리거 완료")
    @PostMapping("/expiry")
    public ResponseEntity<String> triggerExpiry() {
        expiryService.hideExpiredArticles();
        expiryService.deleteGracePeriodExpired();
        return ResponseEntity.ok("expiry triggered");
    }
}
