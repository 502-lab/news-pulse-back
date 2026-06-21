package com.newscurator.controller;

import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.enums.EmailSubscriptionType;
import com.newscurator.dto.request.NotificationSettingsRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.EmailSubscriptionResponse;
import com.newscurator.dto.response.NotificationSettingsResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.EmailSubscriptionService;
import com.newscurator.service.NotificationPreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification Settings", description = "알림 설정 및 이메일 구독 관리 API")
@RestController
public class NotificationSettingsController {

    private final NotificationPreferencesService preferencesService;
    private final EmailSubscriptionService emailSubscriptionService;

    public NotificationSettingsController(
            NotificationPreferencesService preferencesService,
            EmailSubscriptionService emailSubscriptionService) {
        this.preferencesService = preferencesService;
        this.emailSubscriptionService = emailSubscriptionService;
    }

    @Operation(
            summary = "알림 설정 조회",
            description = "현재 계정의 알림 채널별 수신 설정을 반환한다. DB에 저장된 값이 없으면 기본값(전부 true)을 반환한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping("/api/v1/me/notification-settings")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> getSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        NotificationSettingsResponse response = NotificationSettingsResponse.from(
                preferencesService.getOrDefault(userDetails.getAccountId()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "알림 설정 변경",
            description = "알림 채널별(push/email/rising/bias) 수신 여부를 업서트한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @PutMapping("/api/v1/me/notification-settings")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> updateSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody NotificationSettingsRequest request) {
        NotificationSettingsResponse response = NotificationSettingsResponse.from(
                preferencesService.update(userDetails.getAccountId(), request));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "주간 이메일 구독",
            description = "매주 월요일 발송되는 AI 뉴스 브리핑 주간 이메일을 구독한다. 이미 구독 중이면 409를 반환한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "구독 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 구독 중")
    })
    @PostMapping("/api/v1/me/subscriptions/weekly-email")
    public ResponseEntity<ApiResponse<EmailSubscriptionResponse>> subscribeWeeklyEmail(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EmailSubscription sub = emailSubscriptionService.subscribe(
                userDetails.getAccountId(), EmailSubscriptionType.WEEKLY_EMAIL);
        return ResponseEntity.status(201).body(ApiResponse.created(EmailSubscriptionResponse.from(sub)));
    }

    @Operation(
            summary = "주간 이메일 구독 해제",
            description = "주간 이메일 구독을 해제한다. 구독 중이 아니면 404를 반환한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "구독 해제 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "구독 미존재")
    })
    @DeleteMapping("/api/v1/me/subscriptions/weekly-email")
    public ResponseEntity<Void> unsubscribeWeeklyEmail(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        emailSubscriptionService.unsubscribe(userDetails.getAccountId(), EmailSubscriptionType.WEEKLY_EMAIL);
        return ResponseEntity.noContent().build();
    }
}
