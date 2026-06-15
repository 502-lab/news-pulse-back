package com.newscurator.controller;

import com.newscurator.dto.request.EmailVerificationRequestDto;
import com.newscurator.dto.request.EmailVerificationVerifyRequest;
import com.newscurator.dto.response.ApiResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.EmailVerificationService;
import jakarta.validation.Valid;
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

    public EmailVerificationController(EmailVerificationService emailVerificationService,
                                       AccountRepository accountRepository) {
        this.emailVerificationService = emailVerificationService;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestVerification(
            @Valid @RequestBody EmailVerificationRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        accountRepository.findById(userDetails.getAccountId()).ifPresent(
                account -> emailVerificationService.requestCode(account));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @Valid @RequestBody EmailVerificationVerifyRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        accountRepository.findById(userDetails.getAccountId()).ifPresent(account ->
                emailVerificationService.verifyCode(account, request.code()));
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
