package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.dto.request.ReadingPreferenceRequest;
import com.newscurator.dto.response.ReadingPreferenceResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.AuthService;
import com.newscurator.service.ProfileService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    private static final UUID TEST_ACCOUNT_ID = UUID.randomUUID();

    @Mock
    private AuthService authService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ProfileService profileService;

    @Mock
    private Account mockAccount;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CustomUserDetails userDetails =
                new CustomUserDetails(TEST_ACCOUNT_ID, AccountRole.USER, true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MeController meController = new MeController(authService, accountRepository, profileService);
        mockMvc = MockMvcBuilders.standaloneSetup(meController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(mockAccount));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PUT reading-preference: voiceId 포함 → 200, 응답에 voiceId 포함")
    void updateReadingPreference_withVoiceId_returns200AndVoiceId() throws Exception {
        ReadingPreferenceResponse response =
                new ReadingPreferenceResponse(SummaryDepth.BALANCED, ConsumeMode.LISTEN, "harin");
        when(profileService.updateReadingPreference(eq(mockAccount), any(ReadingPreferenceRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/me/reading-preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summaryDepth":"BALANCED","consumeMode":"LISTEN","voiceId":"harin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryDepth").value("BALANCED"))
                .andExpect(jsonPath("$.consumeMode").value("LISTEN"))
                .andExpect(jsonPath("$.voiceId").value("harin"));
    }

    @Test
    @DisplayName("PUT reading-preference: voiceId null → 200, voiceId 필드 null")
    void updateReadingPreference_withoutVoiceId_returns200NullVoiceId() throws Exception {
        ReadingPreferenceResponse response =
                new ReadingPreferenceResponse(SummaryDepth.BALANCED, ConsumeMode.READ, null);
        when(profileService.updateReadingPreference(eq(mockAccount), any(ReadingPreferenceRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/me/reading-preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summaryDepth":"BALANCED","consumeMode":"READ"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceId").doesNotExist());
    }

    @Test
    @DisplayName("PUT reading-preference: 존재하지 않는 voiceId → 422 VALIDATION_FAILED")
    void updateReadingPreference_invalidVoiceId_returns422() throws Exception {
        when(profileService.updateReadingPreference(eq(mockAccount), any(ReadingPreferenceRequest.class)))
                .thenThrow(new IllegalArgumentException("존재하지 않는 voiceId: invalid-id"));

        mockMvc.perform(put("/api/v1/me/reading-preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summaryDepth":"BALANCED","consumeMode":"READ","voiceId":"invalid-id"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
