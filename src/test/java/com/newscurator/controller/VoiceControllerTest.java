package com.newscurator.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newscurator.dto.response.VoiceResponse;
import com.newscurator.exception.GlobalExceptionHandler;
import com.newscurator.service.VoiceService;
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

@ExtendWith(MockitoExtension.class)
class VoiceControllerTest {

    @Mock
    private VoiceService voiceService;

    @InjectMocks
    private VoiceController voiceController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(voiceController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("200: 음성 목록 2건 반환 (previewUrl=null 허용)")
    void getVoices_returns200WithList() throws Exception {
        List<VoiceResponse> voices = List.of(
                new VoiceResponse("harin", "하린", "FEMALE", null),
                new VoiceResponse("junho", "준서", "MALE", null)
        );
        when(voiceService.findAll()).thenReturn(voices);

        mockMvc.perform(get("/api/v1/voices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("harin"))
                .andExpect(jsonPath("$.data[0].gender").value("FEMALE"))
                .andExpect(jsonPath("$.data[1].id").value("junho"));
        // 401 미인증은 JWT 필터 체인에 의해 처리 — SecurityIntegration 테스트에서 검증
    }
}
