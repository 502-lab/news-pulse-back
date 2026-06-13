package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.exception.SummaryNotReadyException;
import com.newscurator.exception.TtsAudioNotFoundException;
import com.newscurator.service.TtsService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TtsControllerTest {

    @Mock private TtsService ttsService;
    @InjectMocks private TtsController ttsController;

    private MockMvc mockMvc;

    private static final Long ARTICLE_ID = 1L;
    private static final String VOICE_ID = "harin";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ttsController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST TTS 신규 요청 → 202 + status=PENDING")
    void postTts_newRequest_returns202WithPending() throws Exception {
        TtsStatusResponse response = new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, "1", VOICE_ID,
                TtsStatus.PENDING, null, null, null);
        when(ttsService.requestArticleTts(eq(ARTICLE_ID), eq(VOICE_ID))).thenReturn(response);

        mockMvc.perform(post("/api/v1/articles/{articleId}/tts", ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceId\":\"harin\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.audioUrl").doesNotExist());
    }

    @Test
    @DisplayName("POST TTS READY 상태 → 200 + audioUrl")
    void postTts_readyStatus_returns200WithAudioUrl() throws Exception {
        TtsStatusResponse response = new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, "1", VOICE_ID,
                TtsStatus.READY, "https://cdn.example.com/tts/article/1/harin.mp3", null, null);
        when(ttsService.requestArticleTts(eq(ARTICLE_ID), eq(VOICE_ID))).thenReturn(response);

        mockMvc.perform(post("/api/v1/articles/{articleId}/tts", ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceId\":\"harin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.audioUrl").isNotEmpty());
    }

    @Test
    @DisplayName("POST TTS 요약 미완료 → 409 SUMMARY_NOT_READY")
    void postTts_summaryNotReady_returns409() throws Exception {
        when(ttsService.requestArticleTts(eq(ARTICLE_ID), eq(VOICE_ID)))
                .thenThrow(new SummaryNotReadyException("기사 요약이 완료되지 않았습니다"));

        mockMvc.perform(post("/api/v1/articles/{articleId}/tts", ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceId\":\"harin\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_NOT_READY"));
    }

    @Test
    @DisplayName("GET TTS 존재하지 않는 항목 → 404")
    void getTtsStatus_notFound_returns404() throws Exception {
        when(ttsService.getArticleTtsStatus(eq(ARTICLE_ID), eq(VOICE_ID)))
                .thenThrow(new TtsAudioNotFoundException("TTS 없음"));

        mockMvc.perform(get("/api/v1/articles/{articleId}/tts", ARTICLE_ID)
                        .param("voiceId", VOICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET TTS READY 상태 → 200 + audioUrl")
    void getTtsStatus_ready_returns200WithAudioUrl() throws Exception {
        TtsStatusResponse response = new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, "1", VOICE_ID,
                TtsStatus.READY, "https://cdn.example.com/tts/article/1/harin.mp3", 30, null);
        when(ttsService.getArticleTtsStatus(eq(ARTICLE_ID), eq(VOICE_ID))).thenReturn(response);

        mockMvc.perform(get("/api/v1/articles/{articleId}/tts", ARTICLE_ID)
                        .param("voiceId", VOICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.audioUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.durationSec").value(30));
    }
}
