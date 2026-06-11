package com.newscurator.controller;

import com.newscurator.dto.response.AccountSummaryResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AuthService authService;
    private final AccountRepository accountRepository;

    public MeController(AuthService authService, AccountRepository accountRepository) {
        this.authService = authService;
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public ResponseEntity<AccountSummaryResponse> getMe(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return accountRepository.findById(userDetails.getAccountId())
                .map(account -> ResponseEntity.ok(authService.buildAccountSummary(account)))
                .orElse(ResponseEntity.notFound().build());
    }
}
