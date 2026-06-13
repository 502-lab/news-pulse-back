package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.BriefingResponse;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.exception.NoFeedArticlesException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.BriefingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BriefingControllerTest {

    @Mock private BriefingService briefingService;
    @Mock private AccountRepository accountRepository;
    @InjectMocks private BriefingController briefingController;

    private MockMvc mockMvc;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(briefingController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication() {
        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        when(userDetails.getAccountId()).thenReturn(ACCOUNT_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, List.of()));
    }

    private TtsStatusResponse buildTts(TtsStatus status) {
        String audioUrl = status == TtsStatus.READY ? "https://cdn.example.com/tts/1.mp3" : null;
        return new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, "1", "Seoyeon",
                status, audioUrl, null, null);
    }

    // ─────────────────────────────────────────────────────────
    // (1) 신규 브리핑 → 202 + ttsItems 배열 포함
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 신규 브리핑 → 202, ttsItems[0].status=PENDING")
    void getTodayBrief_newBrief_returns202WithTtsItems() throws Exception {
        setAuthentication();
        Account account = mock(Account.class);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        TtsStatusResponse ttsItem = buildTts(TtsStatus.PENDING);
        BriefingResponse response = new BriefingResponse(
                LocalDate.now(), List.of(1L), "Seoyeon", List.of(ttsItem));
        when(briefingService.getOrCreateTodayBrief(any(Account.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/briefing/today"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.ttsItems").isArray())
                .andExpect(jsonPath("$.data.ttsItems[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.articleIds[0]").value(1))
                .andExpect(jsonPath("$.data.voiceId").value("Seoyeon"));
    }

    // ─────────────────────────────────────────────────────────
    // (2) 캐시 히트, 전체 READY → 200
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) 캐시 히트·전체 READY → 200, audioUrl non-null")
    void getTodayBrief_cacheHitAllReady_returns200() throws Exception {
        setAuthentication();
        Account account = mock(Account.class);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        TtsStatusResponse readyItem = buildTts(TtsStatus.READY);
        BriefingResponse response = new BriefingResponse(
                LocalDate.now(), List.of(1L), "Seoyeon", List.of(readyItem));
        when(briefingService.getOrCreateTodayBrief(any(Account.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/briefing/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ttsItems[0].status").value("READY"))
                .andExpect(jsonPath("$.data.ttsItems[0].audioUrl").isNotEmpty());
    }

    // ─────────────────────────────────────────────────────────
    // (3) 미인증: @AuthenticationPrincipal = null → 401
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) 미인증 → 401 (SecurityContext 비어있음, userDetails=null)")
    void getTodayBrief_notAuthenticated_returns401() throws Exception {
        // SecurityContext 비워둠 → @AuthenticationPrincipal = null → 401

        mockMvc.perform(get("/api/v1/briefing/today"))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────
    // (4) COMPLETED 0건 → NoFeedArticlesException → 404
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) COMPLETED 기사 0건 → 404 NO_FEED_ARTICLES")
    void getTodayBrief_noCompletedArticles_returns404() throws Exception {
        setAuthentication();
        Account account = mock(Account.class);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(briefingService.getOrCreateTodayBrief(any(Account.class)))
                .thenThrow(new NoFeedArticlesException("완료된 기사 없음"));

        mockMvc.perform(get("/api/v1/briefing/today"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_FEED_ARTICLES"));
    }
}
