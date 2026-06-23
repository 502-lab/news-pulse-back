package com.newscurator.controller;

import com.newscurator.dto.request.AdminPushRequest;
import com.newscurator.dto.request.NoticeCreateRequest;
import com.newscurator.dto.request.NoticePublishRequest;
import com.newscurator.dto.request.NoticeUpdateRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.NoticeResponse;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.admin.AdminPushService;
import com.newscurator.service.admin.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 공지 CRUD + 푸시 발송(008 US4). 전 경로 {@code /api/v1/admin/**} → hasRole(ADMIN).
 */
@Tag(name = "Admin Notices", description = "어드민 공지/푸시 API (ADMIN 전용)")
@RestController
public class AdminNoticeController {

    private final NoticeService noticeService;
    private final AdminPushService adminPushService;

    public AdminNoticeController(NoticeService noticeService, AdminPushService adminPushService) {
        this.noticeService = noticeService;
        this.adminPushService = adminPushService;
    }

    @Operation(summary = "공지 목록(초안 포함)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @GetMapping("/api/v1/admin/notices")
    public ResponseEntity<ApiResponse<List<NoticeResponse>>> list(Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        noticeService.listAll(pageable).map(NoticeResponse::from).getContent()));
    }

    @Operation(summary = "공지 생성", description = "title ≤200, content NOT NULL.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "검증 실패")
    })
    @PostMapping("/api/v1/admin/notices")
    public ResponseEntity<ApiResponse<NoticeResponse>> create(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Valid @RequestBody NoticeCreateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        NoticeResponse.from(
                                noticeService.create(
                                        actor.getAccountId(),
                                        request.title(),
                                        request.content(),
                                        request.published()))));
    }

    @Operation(summary = "공지 수정")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지 없음")
    })
    @PutMapping("/api/v1/admin/notices/{id}")
    public ResponseEntity<ApiResponse<NoticeResponse>> update(
            @AuthenticationPrincipal CustomUserDetails actor,
            @PathVariable Long id,
            @Valid @RequestBody NoticeUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        NoticeResponse.from(
                                noticeService.update(
                                        actor.getAccountId(), id, request.title(), request.content()))));
    }

    @Operation(summary = "공지 게시 토글", description = "published true/false. 공개 노출 즉시 반영.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지 없음")
    })
    @PatchMapping("/api/v1/admin/notices/{id}/publish")
    public ResponseEntity<ApiResponse<Void>> publish(
            @AuthenticationPrincipal CustomUserDetails actor,
            @PathVariable Long id,
            @Valid @RequestBody NoticePublishRequest request) {
        noticeService.setPublished(actor.getAccountId(), id, request.published());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "공지 삭제")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지 없음")
    })
    @DeleteMapping("/api/v1/admin/notices/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable Long id) {
        noticeService.delete(actor.getAccountId(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(
            summary = "공지 푸시 발송",
            description = "활성 사용자에게 공지 푸시. 멱등 키 ADMIN:NOTICE:{noticeId}:{accountId}(재발송 무시).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지 없음")
    })
    @PostMapping("/api/v1/admin/notices/{id}/push")
    public ResponseEntity<ApiResponse<Void>> pushNotice(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Parameter(description = "공지 식별자") @PathVariable Long id) {
        adminPushService.sendNoticePush(actor.getAccountId(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(
            summary = "어드민 캠페인 푸시 발송",
            description = "활성 사용자에게 단발 푸시. 키 ADMIN:CAMPAIGN:{serverUuid}:{accountId}(매 발송 고유 — 의도적 재발송 가능).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PostMapping("/api/v1/admin/notifications/campaign")
    public ResponseEntity<ApiResponse<Void>> pushCampaign(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Valid @RequestBody AdminPushRequest request) {
        adminPushService.sendCampaignPush(actor.getAccountId(), request.title(), request.body());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
