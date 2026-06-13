package com.newscurator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.client.ai.PollyTtsProvider;
import com.newscurator.exception.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.polly.model.Engine;
import software.amazon.awssdk.services.polly.model.OutputFormat;
import software.amazon.awssdk.services.polly.model.PollyException;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechResponse;
import software.amazon.awssdk.services.polly.model.VoiceId;

import java.io.ByteArrayInputStream;

@ExtendWith(MockitoExtension.class)
class PollyTtsProviderTest {

    @Mock private PollyClient pollyClient;

    private PollyTtsProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        // PollyTtsProvider의 생성자는 실제 PollyClient를 생성하므로, 리플렉션으로 mock 주입
        provider = new PollyTtsProvider("ap-northeast-2", "neural");
        var field = PollyTtsProvider.class.getDeclaredField("pollyClient");
        field.setAccessible(true);
        field.set(provider, pollyClient);
    }

    private ResponseInputStream<SynthesizeSpeechResponse> mockResponseStream(byte[] bytes) {
        return new ResponseInputStream<>(
                SynthesizeSpeechResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    // ─────────────────────────────────────────────────────────
    // (1) 정상 호출: voiceId, engine=NEURAL, outputFormat=MP3 검증
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) generate: voiceId=Seoyeon, engine=NEURAL, outputFormat=MP3 로 synthesizeSpeech 호출")
    void generate_callsPollyWithCorrectParameters() throws Exception {
        byte[] mp3Data = {1, 2, 3, 4, 5};
        when(pollyClient.synthesizeSpeech(any(SynthesizeSpeechRequest.class)))
                .thenReturn(mockResponseStream(mp3Data));

        byte[] result = provider.generate("Seoyeon", "테스트 텍스트");

        ArgumentCaptor<SynthesizeSpeechRequest> captor =
                ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(pollyClient).synthesizeSpeech(captor.capture());

        SynthesizeSpeechRequest req = captor.getValue();
        assertThat(req.voiceId()).isEqualTo(VoiceId.SEOYEON);
        assertThat(req.engine()).isEqualTo(Engine.NEURAL);
        assertThat(req.outputFormat()).isEqualTo(OutputFormat.MP3);
        assertThat(result).isEqualTo(mp3Data);
    }

    // ─────────────────────────────────────────────────────────
    // (2) PollyException → AiProviderException 변환
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) PollyException 발생 시 AiProviderException으로 래핑")
    void generate_pollyException_throwsAiProviderException() {
        when(pollyClient.synthesizeSpeech(any(SynthesizeSpeechRequest.class)))
                .thenThrow(PollyException.builder().message("Service unavailable").build());

        assertThatThrownBy(() -> provider.generate("Seoyeon", "텍스트"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("AWS Polly API error");
    }

    // ─────────────────────────────────────────────────────────
    // (3) 2900자 초과 텍스트 → 트런케이트 후 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) 2900자 초과 텍스트는 트런케이트되어 Polly에 전달됨")
    void generate_longText_isTruncatedBeforeCall() throws Exception {
        String longText = "가".repeat(3000);
        when(pollyClient.synthesizeSpeech(any(SynthesizeSpeechRequest.class)))
                .thenReturn(mockResponseStream(new byte[]{9}));

        provider.generate("Seoyeon", longText);

        ArgumentCaptor<SynthesizeSpeechRequest> captor =
                ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(pollyClient).synthesizeSpeech(captor.capture());
        assertThat(captor.getValue().text().length()).isLessThanOrEqualTo(2902); // 2900 + "…"
    }

    // ─────────────────────────────────────────────────────────
    // (4) truncateTtsText 단위 검증
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) truncateTtsText: 2900자 이하 → 그대로, 초과 → 문장 경계 트런케이트 + '…'")
    void truncateTtsText_exactBehavior() {
        String shortText = "A".repeat(2900);
        assertThat(PollyTtsProvider.truncateTtsText(shortText)).isEqualTo(shortText);

        String longText = "A".repeat(2899) + "." + "B".repeat(200);
        String truncated = PollyTtsProvider.truncateTtsText(longText);
        assertThat(truncated).endsWith("…");
        assertThat(truncated.length()).isLessThanOrEqualTo(2902);
    }
}
