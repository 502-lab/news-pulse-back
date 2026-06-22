package com.newscurator.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.response.TrendKeywordResponse;
import com.newscurator.service.TrendQueryService;
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

/** T024: Top5 컨트롤러 (standalone, service mock). */
@ExtendWith(MockitoExtension.class)
class TrendControllerTest {

    @Mock private TrendQueryService trendQueryService;
    @InjectMocks private TrendController trendController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(trendController).build();
    }

    @Test
    @DisplayName("Top5 200: term/count/deltaPct/isNew")
    void getTop5_returnsKeywords() throws Exception {
        when(trendQueryService.getTop5(null)).thenReturn(List.of(
                new TrendKeywordResponse("금리", 12, 50.0, false),
                new TrendKeywordResponse("부동산", 5, null, true)));

        mockMvc.perform(get("/api/v1/trends/keywords/top5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].term").value("금리"))
                .andExpect(jsonPath("$.data[0].count").value(12))
                .andExpect(jsonPath("$.data[0].deltaPct").value(50.0))
                .andExpect(jsonPath("$.data[0].isNew").value(false))
                .andExpect(jsonPath("$.data[1].term").value("부동산"))
                .andExpect(jsonPath("$.data[1].deltaPct").value(nullValue()))
                .andExpect(jsonPath("$.data[1].isNew").value(true));
    }

    @Test
    @DisplayName("카테고리 필터 전달")
    void getTop5_categoryFilter() throws Exception {
        when(trendQueryService.getTop5("POLITICS")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trends/keywords/top5").param("category", "POLITICS"))
                .andExpect(status().isOk());
        verify(trendQueryService).getTop5("POLITICS");
    }

    @Test
    @DisplayName("데이터 없음 → 빈 목록 200")
    void getTop5_empty() throws Exception {
        when(trendQueryService.getTop5(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trends/keywords/top5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("WoW 200: 급상승 목록 + isNew 직렬화")
    void getWow_returnsRising() throws Exception {
        when(trendQueryService.getWow()).thenReturn(List.of(
                new TrendKeywordResponse("신규주간", 8, null, true),
                new TrendKeywordResponse("기존주간", 30, 20.0, false)));

        mockMvc.perform(get("/api/v1/trends/wow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].term").value("신규주간"))
                .andExpect(jsonPath("$.data[0].isNew").value(true))
                .andExpect(jsonPath("$.data[0].deltaPct").value(nullValue()))
                .andExpect(jsonPath("$.data[1].term").value("기존주간"))
                .andExpect(jsonPath("$.data[1].deltaPct").value(20.0));
    }
}
