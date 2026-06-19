package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.NotificationResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "인앱 알림 조회·읽음 처리 API")
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(
            summary = "인앱 알림 목록 조회",
            description = "페이지네이션으로 인앱 알림 목록을 반환한다. "
                    + "unread=true 시 읽지 않은 알림만 반환. "
                    + "알림이 없으면 빈 배열 200 반환 (404 아님). "
                    + "created_at DESC 정렬.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 (빈 목록 포함)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "읽지 않은 알림만 조회. 기본값 false", example = "false")
            @RequestParam(defaultValue = "false") boolean unread,
            @Parameter(description = "페이지 번호 (0-based). 기본값 0", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기. 기본값 20", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationResponse> result = notificationService.listNotifications(
                userDetails.getAccountId(), unread, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
            summary = "알림 단건 읽음 처리",
            description = "지정한 알림을 읽음(is_read=true)으로 표시한다. "
                    + "본인 알림만 처리 가능 — 타인 알림 접근 시 404 반환.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "읽음 처리 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "알림 없음 또는 소유권 없음")
    })
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "읽음 처리할 알림 ID", required = true)
            @PathVariable Long notificationId) {

        NotificationResponse response = notificationService.markRead(
                userDetails.getAccountId(), notificationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "알림 전체 읽음 처리",
            description = "본인의 모든 미읽음 알림을 읽음 처리한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전체 읽음 처리 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        notificationService.markAllRead(userDetails.getAccountId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
