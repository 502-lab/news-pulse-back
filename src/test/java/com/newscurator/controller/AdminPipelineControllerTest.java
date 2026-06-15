package com.newscurator.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.response.PipelineStatsResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.service.PipelineStatsService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminPipelineControllerTest {

    @Mock private PipelineStatsService pipelineStatsService;

    @InjectMocks private AdminPipelineController adminPipelineController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(adminPipelineController)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("200: 통계 응답 구조 확인")
    void getStats_success_returns200WithStatsStructure() throws Exception {
        PipelineStatsResponse stats =
                new PipelineStatsResponse(
                        LocalDate.now(),
                        100L,
                        80.0,
                        5L,
                        Map.of("IT", 30L, "ECONOMY_FINANCE", 20L),
                        new PipelineStatsResponse.PipelineStatus(10L, 5L, 20L, 3L));

        when(pipelineStatsService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/admin/pipeline/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.articlesCollectedToday").value(100))
                .andExpect(jsonPath("$.data.summaryCompletionRate").value(80.0))
                .andExpect(jsonPath("$.data.mergeCount").value(5))
                .andExpect(jsonPath("$.data.pipelineStatus.categoryPending").value(10));
    }

    @Test
    @Disabled("인증 구현 spec 002 이후 활성화")
    @DisplayName("401 UNAUTHORIZED: 인증 없는 요청")
    void getStats_unauthenticated_returns401() throws Exception {
        // spec 002 인증 구현 후 활성화
    }

    @Test
    @Disabled("인증 구현 spec 002 이후 활성화")
    @DisplayName("403 FORBIDDEN: ROLE_ADMIN 없는 사용자")
    void getStats_notAdmin_returns403() throws Exception {
        // spec 002 인증 구현 후 활성화 (US4 AS2)
    }
}
