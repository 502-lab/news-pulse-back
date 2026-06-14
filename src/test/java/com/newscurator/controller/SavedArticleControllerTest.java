package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.dto.response.ArticleItem;
import com.newscurator.dto.response.FeedSummarySlot;
import com.newscurator.dto.response.SavedArticleItem;
import com.newscurator.dto.response.SavedArticleListResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.SavedArticleService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SavedArticleControllerTest {

    @Mock private SavedArticleService savedArticleService;
    @InjectMocks private SavedArticleController savedArticleController;

    private MockMvc mockMvc;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(savedArticleController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        setAuthentication();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication() {
        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        when(userDetails.getAccountId()).thenReturn(ACCOUNT_ID);
        when(userDetails.isEmailVerified()).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, List.of()));
    }

    private SavedArticleItem buildItem(Long articleId) {
        FeedSummarySlot slot = new FeedSummarySlot("요약 내용", "balanced", false);
        ArticleItem article = new ArticleItem(
                articleId, "테스트 기사 " + articleId, "TECH",
                OffsetDateTime.now(), null, slot, null, true);
        return new SavedArticleItem(Instant.now(), article);
    }

    // ─────────────────────────────────────────────────────────
    // (1) listenable=true → service에 listenable=true 전달, READY 기사만 반환
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) listenable=true → service listenable=true 호출, READY 기사 항목 반환")
    void list_listenableTrue_returnsListenableArticles() throws Exception {
        SavedArticleListResponse response = new SavedArticleListResponse(
                List.of(buildItem(1L)), null, false);
        when(savedArticleService.list(eq(ACCOUNT_ID), isNull(), eq(20), eq(true), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/me/saved-articles")
                        .param("listenable", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles").isArray())
                .andExpect(jsonPath("$.data.articles[0].article.id").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(savedArticleService).list(eq(ACCOUNT_ID), isNull(), eq(20), eq(true), isNull());
    }

    // ─────────────────────────────────────────────────────────
    // (2) listenable=false(default) → 기존 동작 회귀 없음
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) listenable 미지정(default=false) → service listenable=false 호출, 모든 기사 반환")
    void list_listenableDefault_returnsAllArticles() throws Exception {
        SavedArticleListResponse response = new SavedArticleListResponse(
                List.of(buildItem(10L), buildItem(11L)), null, false);
        when(savedArticleService.list(eq(ACCOUNT_ID), isNull(), eq(20), eq(false), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/me/saved-articles")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles").isArray())
                .andExpect(jsonPath("$.data.articles.length()").value(2));

        verify(savedArticleService).list(eq(ACCOUNT_ID), isNull(), eq(20), eq(false), isNull());
    }

    // ─────────────────────────────────────────────────────────
    // (3) listenable=true&voiceId=Seoyeon → 해당 음성 READY TTS만 포함
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) listenable=true&voiceId=Seoyeon → service에 voiceId=Seoyeon 전달")
    void list_listenableTrueWithVoiceId_filtersToGivenVoice() throws Exception {
        SavedArticleListResponse response = new SavedArticleListResponse(
                List.of(buildItem(5L)), null, false);
        when(savedArticleService.list(eq(ACCOUNT_ID), isNull(), eq(20), eq(true), eq("Seoyeon")))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/me/saved-articles")
                        .param("listenable", "true")
                        .param("voiceId", "Seoyeon")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles[0].article.id").value(5));

        verify(savedArticleService).list(eq(ACCOUNT_ID), isNull(), eq(20), eq(true), eq("Seoyeon"));
    }
}
