package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.domain.enums.NotificationType;
import com.newscurator.dto.response.NotificationResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.NotificationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private NotificationService notificationService;
    @InjectMocks private NotificationController notificationController;

    private MockMvc mockMvc;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(notificationController)
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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, List.of()));
    }

    private NotificationResponse buildResponse(Long id, boolean read) {
        Instant now = Instant.now();
        return new NotificationResponse(
                id,
                NotificationType.BREAKING,
                "제목",
                "본문",
                null,
                read,
                now,
                now.plus(90, ChronoUnit.DAYS));
    }

    // ─────────────────────────────────────────────────────────
    // (1) GET /me/notifications → 200 (알림 존재)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) GET /me/notifications → 200, 알림 목록 반환")
    void listNotifications_withData_returns200() throws Exception {
        NotificationResponse resp = buildResponse(1L, false);
        when(notificationService.listNotifications(eq(ACCOUNT_ID), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(resp), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].type").value("BREAKING"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ─────────────────────────────────────────────────────────
    // (2) GET /me/notifications → 알림 없어도 200 (빈 배열, 404 금지)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) GET /me/notifications 알림 없음 → 200 빈 배열 (404 아님)")
    void listNotifications_empty_returns200NotEmpty() throws Exception {
        when(notificationService.listNotifications(eq(ACCOUNT_ID), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ─────────────────────────────────────────────────────────
    // (3) GET /me/notifications?unread=true → 200 미읽음 필터 위임
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) GET /me/notifications?unread=true → service에 unread=true 전달, 200")
    void listNotifications_unreadFilter_passedToService() throws Exception {
        NotificationResponse resp = buildResponse(2L, false);
        when(notificationService.listNotifications(eq(ACCOUNT_ID), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of(resp), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/me/notifications").param("unread", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(notificationService).listNotifications(eq(ACCOUNT_ID), eq(true), any());
    }

    // ─────────────────────────────────────────────────────────
    // (4) PATCH /{id}/read → 200 읽음 처리
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) PATCH /me/notifications/{id}/read → 200, isRead=true 반환")
    void markRead_existing_returns200() throws Exception {
        NotificationResponse resp = buildResponse(1L, true);
        when(notificationService.markRead(eq(ACCOUNT_ID), eq(1L))).thenReturn(resp);

        mockMvc.perform(patch("/api/v1/me/notifications/{id}/read", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRead").value(true));
    }

    // ─────────────────────────────────────────────────────────
    // (5) PATCH /{id}/read 다른 account ID → 404
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(5) PATCH /me/notifications/{id}/read 타인 알림 → 404")
    void markRead_notOwnedByAccount_returns404() throws Exception {
        when(notificationService.markRead(eq(ACCOUNT_ID), eq(999L)))
                .thenThrow(new ResourceNotFoundException("Notification", 999L));

        mockMvc.perform(patch("/api/v1/me/notifications/{id}/read", 999L))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────
    // (6) PATCH /read-all → 200
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(6) PATCH /me/notifications/read-all → 200, 서비스 markAllRead 호출")
    void markAllRead_returns200() throws Exception {
        doNothing().when(notificationService).markAllRead(ACCOUNT_ID);

        mockMvc.perform(patch("/api/v1/me/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(notificationService).markAllRead(ACCOUNT_ID);
    }
}
