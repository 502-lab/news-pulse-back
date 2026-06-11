package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.newscurator.dto.request.FeedRequest;
import com.newscurator.dto.response.ArticleFeedResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.service.ArticleFeedService;
import java.util.List;
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
class ArticleFeedControllerTest {

    @Mock private ArticleFeedService articleFeedService;

    @InjectMocks private ArticleFeedController articleFeedController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(articleFeedController)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("200 정상 피드 목록 응답")
    void getFeed_success_returns200() throws Exception {
        ArticleFeedResponse response = new ArticleFeedResponse(List.of(), null, false, 0);
        when(articleFeedService.getFeed(any(FeedRequest.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("200 빈 목록: data=[], nextCursor=null, hasMore=false, size=0 (CHK030)")
    void getFeed_emptyResult_returns200WithEmptyData() throws Exception {
        ArticleFeedResponse emptyResponse = new ArticleFeedResponse(List.of(), null, false, 0);
        when(articleFeedService.getFeed(any(FeedRequest.class))).thenReturn(emptyResponse);

        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.size").value(0));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR: category=INVALID_VALUE (CHK028)")
    void getFeed_invalidCategory_returns400ValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("category", "INVALID_CATEGORY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("size 150 요청 → 100으로 clamp 후 200 응답")
    void getFeed_sizeOver100_clampedTo100AndReturns200() throws Exception {
        ArticleFeedResponse response = new ArticleFeedResponse(List.of(), null, false, 0);
        when(articleFeedService.getFeed(any(FeedRequest.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/articles").param("size", "150"))
                .andExpect(status().isOk());
    }

    @Test
    @Disabled("인증 구현 spec 002 이후 활성화")
    @DisplayName("401 UNAUTHORIZED: 인증 없는 요청")
    void getFeed_unauthenticated_returns401() throws Exception {
        // spec 002 인증 구현 후 활성화
    }

    @Test
    @Disabled("인증 구현 spec 002 이후 활성화")
    @DisplayName("403 FORBIDDEN: 권한 없는 사용자")
    void getFeed_forbidden_returns403() throws Exception {
        // spec 002 인증 구현 후 활성화
    }
}
