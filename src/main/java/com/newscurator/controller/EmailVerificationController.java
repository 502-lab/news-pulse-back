package com.newscurator.controller;

import com.newscurator.dto.request.EmailVerificationRequestDto;
import com.newscurator.dto.request.EmailVerificationVerifyRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.AuthService;
import com.newscurator.service.EmailVerificationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email-verification")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;
    private final AccountRepository accountRepository;
    private final AuthService authService;

    public EmailVerificationController(EmailVerificationService emailVerificationService,
                                       AccountRepository accountRepository,
                                       AuthService authService) {
        this.emailVerificationService = emailVerificationService;
        this.accountRepository = accountRepository;
        this.authService = authService;
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestVerification(
            @Valid @RequestBody EmailVerificationRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        accountRepository.findById(userDetails.getAccountId()).ifPresent(
                account -> emailVerificationService.requestCode(account));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 인증 코드 확인 성공 시 emailVerified=true 가 반영된 정식 JWT(accessToken + refreshToken)를 반환.
     * 프론트는 이 토큰으로 기존 pendingToken을 교체해야 한다.
     */
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @Valid @RequestBody EmailVerificationVerifyRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Map<String, Object> result = authService.completeEmailVerification(
                userDetails.getAccountId(), request.code());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
