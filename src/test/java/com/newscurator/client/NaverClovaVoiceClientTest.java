package com.newscurator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.client.ai.NaverClovaVoiceClient;
import com.newscurator.exception.AiProviderException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

class NaverClovaVoiceClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private static final String TEST_KEY_ID = "test-ncp-key-id-value";
    private static final String TEST_KEY = "test-ncp-api-key-value";

    private NaverClovaVoiceClient client;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        client = new NaverClovaVoiceClient(wireMock.baseUrl(), TEST_KEY_ID, TEST_KEY);

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("200 응답 → MP3 bytes 반환")
    void generate_200_returnsMp3Bytes() {
        byte[] expectedMp3 = {0x49, 0x44, 0x33}; // ID3 헤더 시뮬레이션
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(expectedMp3)));

        byte[] result = client.generate("harin", "테스트 텍스트");

        assertThat(result).isEqualTo(expectedMp3);
    }

    @Test
    @DisplayName("4xx 응답 → AiProviderException")
    void generate_4xx_throwsAiProviderException() {
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.generate("harin", "텍스트"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("5xx 응답 → AiProviderException")
    void generate_5xx_throwsAiProviderException() {
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.generate("harin", "텍스트"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("X-NCP-APIGW-API-KEY-ID 헤더 전송 확인")
    void generate_sendsNcpKeyIdHeader() {
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(new byte[]{1, 2, 3})));

        client.generate("harin", "헤더 테스트");

        wireMock.verify(postRequestedFor(urlEqualTo("/tts-premium/v1/tts"))
                .withHeader("X-NCP-APIGW-API-KEY-ID", equalTo(TEST_KEY_ID))
                .withHeader("X-NCP-APIGW-API-KEY", equalTo(TEST_KEY)));
    }

    @Test
    @DisplayName("form body에 speaker/text 파라미터 포함 확인")
    void generate_sendsFormBodyWithSpeakerAndText() {
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(new byte[]{1, 2, 3})));

        client.generate("junho", "안녕하세요");

        wireMock.verify(postRequestedFor(urlEqualTo("/tts-premium/v1/tts"))
                .withRequestBody(containing("speaker=junho"))
                .withRequestBody(containing("format=mp3")));
    }

    @Test
    @DisplayName("1900자 초과 텍스트 → truncateTtsText가 1901자 이하로 줄이고 '…' 포함")
    void truncateTtsText_longText_truncatesWithEllipsis() {
        // 문장 경계 없음 → 1900자 하드컷 + "…"
        String hardCut = NaverClovaVoiceClient.truncateTtsText("가".repeat(2000));
        assertThat(hardCut).endsWith("…");
        assertThat(hardCut.length()).isLessThanOrEqualTo(1901);

        // 1850번째 위치에 마침표 → 마침표 직후에서 커트
        String withPeriod = "가".repeat(1850) + "." + "나".repeat(200);
        String result = NaverClovaVoiceClient.truncateTtsText(withPeriod);
        assertThat(result).endsWith(".…");
        assertThat(result.length()).isLessThanOrEqualTo(1901);

        // 1900자 이하 → 그대로
        String shortText = "가".repeat(1900);
        assertThat(NaverClovaVoiceClient.truncateTtsText(shortText)).isEqualTo(shortText);
    }

    @Test
    @DisplayName("API 키 값이 로그에 출력되지 않음")
    void generate_apiKeyNotLeakedToLog() {
        wireMock.stubFor(post(urlPathEqualTo("/tts-premium/v1/tts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(new byte[]{1, 2, 3})));

        client.generate("harin", "로그 누출 테스트");

        List<ILoggingEvent> logLines = logAppender.list;
        for (ILoggingEvent event : logLines) {
            String msg = event.getFormattedMessage();
            assertThat(msg).as("API 키 ID가 로그에 노출되면 안 됨").doesNotContain(TEST_KEY_ID);
            assertThat(msg).as("API 키가 로그에 노출되면 안 됨").doesNotContain(TEST_KEY);
        }
    }
}
