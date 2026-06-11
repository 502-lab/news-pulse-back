package com.newscurator.controller;

import com.newscurator.dto.request.PasswordResetConfirmRequest;
import com.newscurator.dto.request.PasswordResetRequestDto;
import com.newscurator.dto.request.PasswordResetVerifyRequest;
import com.newscurator.dto.response.PasswordResetVerifyResponse;
import com.newscurator.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Password Reset", description = "비밀번호 재설정 3단계 흐름 (모두 인증 불필요)")
@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @Operation(summary = "재설정 코드 요청",
               description = "이메일로 6자리 재설정 코드 발송. 미등록/소셜 전용 계정도 동일한 202 반환 (열거 방지). "
                           + "소셜 전용 계정은 소셜 로그인 이용 안내 이메일 발송.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "요청 처리됨 (이메일 발송 여부 미공개)"),
        @ApiResponse(responseCode = "429", description = "시간당 발송 한도 초과 (등록 계정만)"),
        @ApiResponse(responseCode = "503", description = "이메일 발송 실패")
    })
    @PostMapping("/request")
    public ResponseEntity<Void> requestCode(@RequestBody @Valid PasswordResetRequestDto request) {
        passwordResetService.requestCode(request.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "재설정 코드 검증",
               description = "6자리 코드 검증 후 단일 사용 resetToken 발급. "
                           + "10분 TTL, 1회만 사용 가능. 5회 오입력 시 코드 무효화.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검증 성공, resetToken 반환"),
        @ApiResponse(responseCode = "401", description = "코드 불일치 또는 계정 없음"),
        @ApiResponse(responseCode = "410", description = "코드 만료")
    })
    @PostMapping("/verify")
    public ResponseEntity<PasswordResetVerifyResponse> verifyCode(
            @RequestBody @Valid PasswordResetVerifyRequest request) {
        PasswordResetVerifyResponse response =
                passwordResetService.verifyCode(request.email(), request.code());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "비밀번호 재설정 확인",
               description = "resetToken과 새 비밀번호로 비밀번호 변경. "
                           + "성공 시 해당 계정의 모든 리프레시 토큰 무효화 (FR-025). "
                           + "resetToken 재사용 시 401.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "비밀번호 변경 성공"),
        @ApiResponse(responseCode = "401", description = "resetToken 유효하지 않거나 이미 사용됨"),
        @ApiResponse(responseCode = "422", description = "비밀번호 정책 위반")
    })
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(
            @RequestBody @Valid PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.resetToken(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
