package com.newscurator.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.dto.response.SummarySlot;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.service.ArticleDetailService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ArticleDetailControllerTest {

    @Mock private ArticleDetailService articleDetailService;

    @InjectMocks private ArticleDetailController articleDetailController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(articleDetailController)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("200: 모든 슬롯 포함 정상 응답")
    void getDetail_success_returns200WithAllSlots() throws Exception {
        ArticleDetailResponse response = buildDetailResponse("COMPLETED", "요약 내용");
        when(articleDetailService.getDetail(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/articles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Test Article"))
                .andExpect(jsonPath("$.data.brief.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.balanced.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.deep.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("200: deep FAILED → content=null, status=FAILED (CHK022)")
    void getDetail_deepFailed_returns200WithNullContent() throws Exception {
        ArticleDetailResponse response = buildDetailResponse("FAILED", null);
        when(articleDetailService.getDetail(2L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/articles/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deep.status").value("FAILED"))
                .andExpect(jsonPath("$.data.deep.content").doesNotExist());
    }

    @Test
    @DisplayName("404 ARTICLE_NOT_FOUND: 존재하지 않는 기사")
    void getDetail_articleNotFound_returns404() throws Exception {
        when(articleDetailService.getDetail(999L)).thenThrow(new ArticleNotFoundException(999L));

        mockMvc.perform(get("/api/v1/articles/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
    }

    private ArticleDetailResponse buildDetailResponse(String deepStatus, String deepContent) {
        return new ArticleDetailResponse(
                1L,
                "Test Article",
                null,
                "https://example.com/news/1",
                "IT",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                new SummarySlot("COMPLETED", "핵심 요약", OffsetDateTime.now()),
                new SummarySlot("COMPLETED", "균형 요약", OffsetDateTime.now()),
                new SummarySlot(
                        deepStatus,
                        deepContent,
                        deepStatus.equals("COMPLETED") ? OffsetDateTime.now() : null));
    }
}
