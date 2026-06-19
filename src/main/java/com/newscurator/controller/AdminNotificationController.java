package com.newscurator.controller;

import com.newscurator.dto.request.AdminNotificationRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.service.AdminNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Notifications", description = "어드민 알림 수동 발송 API")
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    public AdminNotificationController(AdminNotificationService adminNotificationService) {
        this.adminNotificationService = adminNotificationService;
    }

    @Operation(
            summary = "어드민 알림 수동 발송",
            description = "대상(ALL / 특정 계정 목록 / 토픽 구독자)에게 시스템 알림을 비동기 enqueue한다. "
                    + "발송 요청만 수락하고 실제 발송은 outbox processor가 처리한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "발송 요청 수락"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "요청 검증 실패")
    })
    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> send(@Valid @RequestBody AdminNotificationRequest request) {
        adminNotificationService.sendNotification(request);
        return ResponseEntity.accepted().body(ApiResponse.accepted(null));
    }
}
