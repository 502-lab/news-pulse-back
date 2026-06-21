package com.newscurator.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.dto.response.BiasSpectrumResponse;
import com.newscurator.dto.response.OutletBiasResponse;
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

    // ── 출처 편향 집계 (FR-006) ──────────────────────────────────────

    @Test
    @DisplayName("출처 집계 200: biasValue·articleCount 반환")
    void getOutletBias_returnsAggregate() throws Exception {
        when(biasAnalysisService.getOutletBias(1L))
                .thenReturn(new OutletBiasResponse(1L, -23.5, 142));

        mockMvc.perform(get("/api/v1/bias/outlets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceId").value(1))
                .andExpect(jsonPath("$.data.biasValue").value(-23.5))
                .andExpect(jsonPath("$.data.articleCount").value(142));
    }

    @Test
    @DisplayName("출처 집계 200: 10건 미만 → biasValue null, articleCount 그대로")
    void getOutletBias_belowMin_biasValueNull() throws Exception {
        when(biasAnalysisService.getOutletBias(2L))
                .thenReturn(new OutletBiasResponse(2L, null, 4));

        mockMvc.perform(get("/api/v1/bias/outlets/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.biasValue").value(nullValue()))
                .andExpect(jsonPath("$.data.articleCount").value(4));
    }

    @Test
    @DisplayName("출처 집계 404: 출처 없음")
    void getOutletBias_sourceNotFound_returns404() throws Exception {
        when(biasAnalysisService.getOutletBias(99L))
                .thenThrow(new ResourceNotFoundException("Source", 99L));

        mockMvc.perform(get("/api/v1/bias/outlets/99"))
                .andExpect(status().isNotFound());
    }

    // ── 전체 스펙트럼 (FR-007) ───────────────────────────────────────

    @Test
    @DisplayName("스펙트럼 200: 가중평균 + 진보/중립/보수 % 반환")
    void getSpectrum_returnsDistribution() throws Exception {
        when(biasAnalysisService.getSpectrum())
                .thenReturn(new BiasSpectrumResponse(-12.3, 42.5, 38.2, 19.3, 5123));

        mockMvc.perform(get("/api/v1/bias/spectrum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weightedAverage").value(-12.3))
                .andExpect(jsonPath("$.data.liberalPercent").value(42.5))
                .andExpect(jsonPath("$.data.neutralPercent").value(38.2))
                .andExpect(jsonPath("$.data.conservativePercent").value(19.3))
                .andExpect(jsonPath("$.data.totalCount").value(5123));
    }

    @Test
    @DisplayName("스펙트럼 200: 기사 0건 → 집계 값 모두 null, totalCount=0")
    void getSpectrum_empty_allNull() throws Exception {
        when(biasAnalysisService.getSpectrum())
                .thenReturn(new BiasSpectrumResponse(null, null, null, null, 0));

        mockMvc.perform(get("/api/v1/bias/spectrum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weightedAverage").value(nullValue()))
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    // ── Backfill (admin) ─────────────────────────────────────────────
    // 인증/인가(401/403)는 SecurityConfig 로딩이 필요하므로 BiasAuthorizationIT(@SpringBootTest)에서 검증.
    // 여기서는 202 + created 값 + 멱등(2회차 0) 의미만 검증.

    @Test
    @DisplayName("backfill 202: created 반환")
    void backfill_returns202WithCreated() throws Exception {
        when(biasAnalysisService.backfill()).thenReturn(5494L);

        mockMvc.perform(post("/api/v1/admin/bias/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.data.created").value(5494));
    }

    @Test
    @DisplayName("backfill 멱등: 2회차 created=0")
    void backfill_idempotent_secondZero() throws Exception {
        when(biasAnalysisService.backfill()).thenReturn(5494L).thenReturn(0L);

        mockMvc.perform(post("/api/v1/admin/bias/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.created").value(5494));
        mockMvc.perform(post("/api/v1/admin/bias/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.created").value(0));
    }
}
