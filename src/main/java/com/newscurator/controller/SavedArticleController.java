package com.newscurator.controller;

import com.newscurator.dto.response.ApiResponse;
import com.newscurator.dto.response.SavedArticleListResponse;
import com.newscurator.exception.EmailNotVerifiedException;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.SavedArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Saved Articles", description = "기사 저장 / 해제 / 목록 조회")
@Validated
@RestController
public class SavedArticleController {

    private final SavedArticleService savedArticleService;

    public SavedArticleController(SavedArticleService savedArticleService) {
        this.savedArticleService = savedArticleService;
    }

    @Operation(
            summary = "기사 저장",
            description = "기사를 저장 목록에 추가한다. "
                    + "이미 저장된 기사 재저장 시 멱등 200 반환. "
                    + "저장 상한(1,000건) 초과 시 409 반환. "
                    + "존재하지 않는 기사 ID는 404 반환.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신규 저장 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이미 저장됨 (멱등)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일 인증 미완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "기사 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "저장 상한(1,000건) 초과")
    })
    @PostMapping("/api/v1/articles/{articleId}/save")
    public ResponseEntity<ApiResponse<Void>> save(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "저장할 기사 ID", required = true)
            @PathVariable Long articleId) {

        verifyEmail(userDetails);
        boolean isNew = savedArticleService.save(userDetails.getAccountId(), articleId);
        HttpStatus status = isNew ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(null));
    }

    @Operation(
            summary = "기사 저장 해제",
            description = "저장 목록에서 기사를 제거한다. "
                    + "저장하지 않은 기사 해제 시 멱등 204 반환.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "해제 완료 (또는 원래 미저장)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일 인증 미완료")
    })
    @DeleteMapping("/api/v1/articles/{articleId}/save")
    public ResponseEntity<Void> unsave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "해제할 기사 ID", required = true)
            @PathVariable Long articleId) {

        verifyEmail(userDetails);
        savedArticleService.unsave(userDetails.getAccountId(), articleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "저장 기사 목록 조회",
            description = "현재 사용자의 저장 기사 목록을 savedAt 역순으로 반환한다. "
                    + "cursor 기반 페이지네이션 지원. "
                    + "listenable=true이면 READY TTS가 존재하는 기사만 반환(들을 수 있음 필터). "
                    + "voiceId 지정 시 해당 음성의 READY TTS가 있는 기사만 포함.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 조회 성공 (0건 포함)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일 인증 미완료")
    })
    @GetMapping("/api/v1/me/saved-articles")
    public ResponseEntity<ApiResponse<SavedArticleListResponse>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "이전 응답의 nextCursor 값")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 (1~50, 기본값 20)")
            @Min(1) @Max(50)
            @RequestParam(required = false, defaultValue = "20") int size,
            @Parameter(description = "READY TTS가 존재하는 기사만 반환 (들을 수 있음 필터, 기본값 false)")
            @RequestParam(defaultValue = "false") boolean listenable,
            @Parameter(description = "특정 음성 ID의 READY TTS만 포함 (listenable=true 시 유효, 미지정 시 모든 음성)")
            @RequestParam(required = false) String voiceId) {

        verifyEmail(userDetails);
        SavedArticleListResponse response =
                savedArticleService.list(userDetails.getAccountId(), cursor, size, listenable, voiceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private void verifyEmail(CustomUserDetails userDetails) {
        if (!userDetails.isEmailVerified()) {
            throw new EmailNotVerifiedException("이메일 인증이 필요합니다");
        }
    }
}
