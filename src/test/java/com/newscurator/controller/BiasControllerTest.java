package com.newscurator.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.service.BiasAnalysisService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T026: 편향 칩 API(FR-009) 컨트롤러 테스트. */
@ExtendWith(MockitoExtension.class)
class BiasControllerTest {

    @Mock private BiasAnalysisService biasAnalysisService;

    @InjectMocks private BiasController biasController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(biasController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("200 DONE: value·rationaleKeywords·status 반환")
    void getBias_done_returnsScore() throws Exception {
        when(biasAnalysisService.getBiasForArticle(1L))
                .thenReturn(new BiasScoreResponse(-45, List.of("편향적 프레이밍", "단일 시각"), "DONE"));

        mockMvc.perform(get("/api/v1/articles/1/bias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.value").value(-45))
                .andExpect(jsonPath("$.data.rationaleKeywords[0]").value("편향적 프레이밍"))
                .andExpect(jsonPath("$.data.rationaleKeywords[1]").value("단일 시각"))
                .andExpect(jsonPath("$.data.status").value("DONE"));
    }

    @Test
    @DisplayName("200 PENDING: 필드 포함되되 value·rationaleKeywords는 null, status만 채움")
    void getBias_pending_fieldsPresentButNull() throws Exception {
        when(biasAnalysisService.getBiasForArticle(2L))
                .thenReturn(new BiasScoreResponse(null, null, "PENDING"));

        mockMvc.perform(get("/api/v1/articles/2/bias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.value").value(nullValue()))
                .andExpect(jsonPath("$.data.rationaleKeywords").value(nullValue()))
                // raw 본문 단언: jsonPath nullValue는 omit/null을 구분 못 하므로 키 존재 + null을 직접 확인
                .andExpect(content().string(containsString("\"value\":null")))
                .andExpect(content().string(containsString("\"rationaleKeywords\":null")))
                .andExpect(content().string(containsString("\"status\":\"PENDING\"")));
    }

    @Test
    @DisplayName("404: BiasAnalysis 행 없음")
    void getBias_noRow_returns404() throws Exception {
        when(biasAnalysisService.getBiasForArticle(99L))
                .thenThrow(new ResourceNotFoundException("BiasAnalysis", 99L));

        mockMvc.perform(get("/api/v1/articles/99/bias"))
                .andExpect(status().isNotFound());
    }
}
