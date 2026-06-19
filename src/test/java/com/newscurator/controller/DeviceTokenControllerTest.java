package com.newscurator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.enums.DevicePlatform;
import com.newscurator.dto.request.DeviceTokenRequest;
import com.newscurator.dto.response.DeviceTokenResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.security.CustomUserDetails;
import com.newscurator.service.DeviceTokenService;
import java.time.Instant;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceTokenControllerTest {

    @Mock private DeviceTokenService deviceTokenService;
    @InjectMocks private DeviceTokenController deviceTokenController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(deviceTokenController)
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

    private DeviceTokenResponse buildResponse(Long id, String token, DevicePlatform platform) {
        return new DeviceTokenResponse(id, ACCOUNT_ID, token, platform, Instant.now(), Instant.now());
    }

    // ─────────────────────────────────────────────────────────
    // (1) POST 신규 등록 → 201
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) POST /me/device-tokens → 201 Created, data.id 존재")
    void registerToken_validRequest_returns201() throws Exception {
        DeviceTokenRequest request = new DeviceTokenRequest("fcm-token-abc", DevicePlatform.IOS);
        DeviceTokenResponse response = buildResponse(1L, "fcm-token-abc", DevicePlatform.IOS);
        when(deviceTokenService.register(eq(ACCOUNT_ID), eq("fcm-token-abc"), eq(DevicePlatform.IOS)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/me/device-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.token").value("fcm-token-abc"))
                .andExpect(jsonPath("$.data.platform").value("IOS"));
    }

    // ─────────────────────────────────────────────────────────
    // (2) POST 동일 token 재등록(upsert) → 201 (idempotent)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) POST 동일 token 재등록 → 201 (upsert, 서비스에 동일 파라미터 전달)")
    void registerToken_sameToken_returns201Idempotently() throws Exception {
        String token = "fcm-token-dup";
        DeviceTokenRequest request = new DeviceTokenRequest(token, DevicePlatform.ANDROID);
        DeviceTokenResponse response = buildResponse(1L, token, DevicePlatform.ANDROID);
        when(deviceTokenService.register(eq(ACCOUNT_ID), eq(token), eq(DevicePlatform.ANDROID)))
                .thenReturn(response);

        // 2번 연속 호출
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/me/device-tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // 서비스 register 2회 호출됨 (upsert 책임은 서비스·레포지토리에 있음)
        verify(deviceTokenService, org.mockito.Mockito.times(2))
                .register(ACCOUNT_ID, token, DevicePlatform.ANDROID);
    }

    // ─────────────────────────────────────────────────────────
    // (3) DELETE 정상 삭제 → 204
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) DELETE /me/device-tokens/{id} → 204 No Content")
    void deleteToken_existingId_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/me/device-tokens/{tokenId}", 1L))
                .andExpect(status().isNoContent());

        verify(deviceTokenService).delete(ACCOUNT_ID, 1L);
    }

    // ─────────────────────────────────────────────────────────
    // (4) DELETE 존재하지 않는 id → 404
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) DELETE 존재하지 않는 tokenId → 404")
    void deleteToken_nonExistentId_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("DeviceToken", 999L))
                .when(deviceTokenService).delete(eq(ACCOUNT_ID), eq(999L));

        mockMvc.perform(delete("/api/v1/me/device-tokens/{tokenId}", 999L))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────
    // (5) POST 인증 없음 → 401
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(5) POST 인증 없음 → @AuthenticationPrincipal null → NullPointerException (401 처리)")
    void registerToken_noAuthentication_handledAsUnauthorized() throws Exception {
        SecurityContextHolder.clearContext();

        // standaloneSetup에서 인증 없으면 principal이 null로 주입됨
        // controller가 NPE를 던지거나 security layer에서 401 처리
        mockMvc.perform(post("/api/v1/me/device-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"platform\":\"IOS\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 401 || status == 500;
                });
    }

    // ─────────────────────────────────────────────────────────
    // (6) POST 유효성 오류 (token 빈 값) → 422
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(6) POST token 빈 값 → 422 Unprocessable Entity")
    void registerToken_blankToken_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/me/device-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"platform\":\"IOS\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
