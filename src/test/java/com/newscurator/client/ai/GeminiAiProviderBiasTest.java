package com.newscurator.client.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.config.AiProperties;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.AiTransientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** T012: GeminiAiProvider.analyzeBias 단위 테스트 (WireMock 스텁). */
class GeminiAiProviderBiasTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private GeminiAiProvider provider;

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        AiProperties aiProperties =
                new AiProperties(10, 3, 0L, new AiProperties.DeepRetryProperties(60, 5));
        provider = new GeminiAiProvider(
                restClient, "test-api-key", "gemini-2.0-flash", wireMock.baseUrl(), aiProperties,
                new ObjectMapper());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Gemini 응답 본문을 Jackson으로 직렬화 — innerText의 따옴표·개행·코드펜스를 정확히 이스케이프
    private static String geminiJson(String innerText) {
        try {
            return MAPPER.writeValueAsString(java.util.Map.of(
                    "candidates",
                    java.util.List.of(java.util.Map.of(
                            "content",
                            java.util.Map.of("parts",
                                    java.util.List.of(java.util.Map.of("text", innerText)))))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stub(String innerText) {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson(innerText))));
    }

    @Test
    @DisplayName("정상 JSON: score·keywords 파싱")
    void analyzeBias_validJson_parsed() {
        stub("{\"score\": -45, \"keywords\": [\"편향적 프레이밍\", \"단일 시각\"]}");

        BiasAnalysisResult result = provider.analyzeBias("제목", "내용");

        assertThat(result.value()).isEqualTo(-45);
        assertThat(result.rationaleKeywords())
                .containsExactly("편향적 프레이밍", "단일 시각");
    }

    @Test
    @DisplayName("마크다운 코드펜스로 감싼 JSON도 파싱")
    void analyzeBias_codeFenced_parsed() {
        // 실제 개행·따옴표를 가진 자연스러운 펜스 텍스트 (Jackson이 본문 직렬화 시 이스케이프)
        stub("```json\n{\"score\": 34, \"keywords\": [\"보수 편향\", \"강조\"]}\n```");

        BiasAnalysisResult result = provider.analyzeBias("제목", "내용");

        assertThat(result.value()).isEqualTo(34);
        assertThat(result.rationaleKeywords()).hasSize(2);
    }

    @Test
    @DisplayName("score 범위 초과(-100~100) → AiProviderException")
    void analyzeBias_scoreOutOfRange_throws() {
        stub("{\"score\": 150, \"keywords\": [\"a\", \"b\"]}");

        assertThatThrownBy(() -> provider.analyzeBias("제목", "내용"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("keywords 개수 부족(<2) → AiProviderException")
    void analyzeBias_tooFewKeywords_throws() {
        stub("{\"score\": 0, \"keywords\": [\"사실 보도\"]}");

        assertThatThrownBy(() -> provider.analyzeBias("제목", "내용"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("파싱 불가 텍스트 → AiProviderException(결정적, 재시도 비대상)")
    void analyzeBias_unparseable_throws() {
        stub("그냥 텍스트 응답, JSON 아님");

        assertThatThrownBy(() -> provider.analyzeBias("제목", "내용"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("429 → AiTransientException(재시도 대상)")
    void analyzeBias_429_transient() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> provider.analyzeBias("제목", "내용"))
                .isInstanceOf(AiTransientException.class);
    }

    @Test
    @DisplayName("5xx → AiTransientException(재시도 대상)")
    void analyzeBias_5xx_transient() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider.analyzeBias("제목", "내용"))
                .isInstanceOf(AiTransientException.class);
    }
}
