package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.NoticeResponse;
import com.newscurator.service.admin.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 공지 조회(008 US4). {@code GET /api/v1/notices} = permitAll. 게시(published=true) 공지만 노출.
 */
@Tag(name = "Notices", description = "공개 공지 조회 API")
@RestController
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @Operation(summary = "공개 공지 목록", description = "게시 상태(published=true) 공지만. 공개(인증 불필요).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(없으면 빈 목록)")
    })
    @GetMapping("/api/v1/notices")
    public ResponseEntity<ApiResponse<List<NoticeResponse>>> publicList() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        noticeService.listPublished().stream().map(NoticeResponse::from).toList()));
    }
}
