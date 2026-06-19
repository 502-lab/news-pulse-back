package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.enums.AdminTargetType;
import com.newscurator.domain.enums.NotificationTopic;
import com.newscurator.dto.request.AdminNotificationRequest;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.AdminNotificationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminNotificationControllerTest {

    @Mock private AdminNotificationService adminNotificationService;

    @InjectMocks private AdminNotificationController adminNotificationController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminNotificationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        setAuthentication();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication() {
        CustomUserDetails userDetails = org.mockito.Mockito.mock(CustomUserDetails.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, List.of()));
    }

    // ─────────────────────────────────────────────────────────
    // Happy paths: ADMIN + 각 targetType → 202
    // (standaloneSetup에서 @PreAuthorize 미실행 — 인증 단위테스트는 통합 테스트 또는 Spring Security Test 활용)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("targetType=ALL → 202 Accepted")
    void send_targetTypeAll_returns202() throws Exception {
        AdminNotificationRequest request = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.ALL, null, null);

        mockMvc.perform(post("/api/v1/admin/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.status").value("success"));

        verify(adminNotificationService).sendNotification(any());
    }

    @Test
    @DisplayName("targetType=ACCOUNT_IDS → 202 Accepted")
    void send_targetTypeAccountIds_returns202() throws Exception {
        AdminNotificationRequest request = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.ACCOUNT_IDS, List.of(UUID.randomUUID()), null);

        mockMvc.perform(post("/api/v1/admin/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202));

        verify(adminNotificationService).sendNotification(any());
    }

    @Test
    @DisplayName("targetType=TOPIC_SUBSCRIBERS + topic=BRIEFING → 202 Accepted")
    void send_targetTypeTopicSubscribers_returns202() throws Exception {
        AdminNotificationRequest request = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.TOPIC_SUBSCRIBERS, null, NotificationTopic.BRIEFING);

        mockMvc.perform(post("/api/v1/admin/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202));

        verify(adminNotificationService).sendNotification(any());
    }

    @Test
    @DisplayName("422: title 누락 시 검증 실패")
    void send_missingTitle_returns422() throws Exception {
        String json = """
                {"body":"내용","targetType":"ALL"}
                """;

        mockMvc.perform(post("/api/v1/admin/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity());
    }

    @Disabled("standaloneSetup에서는 @PreAuthorize 미동작 — Spring Security 통합 테스트 필요 (spec 완성 후 활성화)")
    @Test
    @DisplayName("403: USER 권한으로 호출 시 FORBIDDEN")
    void send_userRole_returns403() throws Exception {
        // Spring Security 통합 테스트 환경에서 ROLE_USER principal로 호출 → 403 확인
    }
}
