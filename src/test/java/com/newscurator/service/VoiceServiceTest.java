package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Voice;
import com.newscurator.dto.response.VoiceResponse;
import com.newscurator.repository.VoiceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceServiceTest {

    @Mock
    private VoiceRepository voiceRepository;

    private VoiceService voiceService;

    @BeforeEach
    void setUp() {
        voiceService = new VoiceService(voiceRepository);
    }

    @Test
    @DisplayName("findAll: 1건 VoiceResponse 반환 (Seoyeon)")
    void findAll_returnsSeoyeonVoiceResponse() {
        Voice seoyeon = Voice.builder().id("Seoyeon").name("서연").gender("FEMALE").previewUrl(null).build();
        when(voiceRepository.findAll()).thenReturn(List.of(seoyeon));

        List<VoiceResponse> result = voiceService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result).extracting(VoiceResponse::id).containsExactly("Seoyeon");
        assertThat(result).extracting(VoiceResponse::gender).containsExactly("FEMALE");
        // previewUrl은 시드 NULL이므로 null 허용
        assertThat(result).extracting(VoiceResponse::previewUrl).containsOnlyNulls();
    }

    @Test
    @DisplayName("validateVoiceId: 유효한 ID → exception 없음")
    void validateVoiceId_validId_noException() {
        when(voiceRepository.existsById("Seoyeon")).thenReturn(true);

        voiceService.validateVoiceId("Seoyeon"); // must not throw
    }

    @Test
    @DisplayName("validateVoiceId: 존재하지 않는 ID → IllegalArgumentException (422)")
    void validateVoiceId_invalidId_throwsIllegalArgument() {
        when(voiceRepository.existsById("unknown-voice")).thenReturn(false);

        assertThatThrownBy(() -> voiceService.validateVoiceId("unknown-voice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-voice");
    }
}
