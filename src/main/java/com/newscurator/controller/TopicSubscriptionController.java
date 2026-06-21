package com.newscurator.controller;

import com.newscurator.dto.request.TopicSubscriptionsRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.TopicSubscriptionsResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.TopicSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Topic Subscriptions", description = "알림 토픽 구독 조회·교체")
@RestController
@RequestMapping("/api/v1/me/topic-subscriptions")
public class TopicSubscriptionController {

    private final TopicSubscriptionService topicSubscriptionService;

    public TopicSubscriptionController(TopicSubscriptionService topicSubscriptionService) {
        this.topicSubscriptionService = topicSubscriptionService;
    }

    @Operation(
            summary = "토픽 구독 목록 조회",
            description = "현재 사용자의 알림 토픽 구독 목록을 반환한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<TopicSubscriptionsResponse>> getTopics(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        TopicSubscriptionsResponse response = topicSubscriptionService.getTopics(userDetails.getAccountId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "토픽 구독 교체 (replace-all)",
            description = "기존 구독 목록을 전부 삭제하고 전달된 토픽 목록으로 교체한다. "
                    + "빈 목록 전달 시 전체 구독 해제.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "교체 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "요청 본문 유효성 오류")
    })
    @PutMapping
    public ResponseEntity<ApiResponse<TopicSubscriptionsResponse>> replaceTopics(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody TopicSubscriptionsRequest request) {

        TopicSubscriptionsResponse response = topicSubscriptionService.replaceAll(
                userDetails.getAccountId(), request.topics());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
