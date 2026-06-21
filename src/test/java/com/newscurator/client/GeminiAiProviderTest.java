package com.newscurator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.client.ai.GeminiAiProvider;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.exception.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class GeminiAiProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private GeminiAiProvider provider;

    private static final String CLASSIFY_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "ECONOMY_FINANCE"}]
                  }
                }
              ]
            }
            """;

    private static final String UNKNOWN_CATEGORY_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "UNKNOWN_CATEGORY"}]
                  }
                }
              ]
            }
            """;

    private static final String SUMMARIZE_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "경제 뉴스 요약 내용입니다."}]
                  }
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        // SimpleClientHttpRequestFactory enforces HTTP/1.1 (WireMock does not support HTTP/2)
        RestClient restClient =
                RestClient.builder()
                        .requestFactory(new SimpleClientHttpRequestFactory())
                        .build();
        AiProperties.DeepRetryProperties deepRetry = new AiProperties.DeepRetryProperties(60, 5);
        AiProperties aiProperties = new AiProperties(10, 3, 0L, deepRetry);
        provider = new GeminiAiProvider(
                restClient, "test-api-key", "gemini-2.0-flash", wireMock.baseUrl(), aiProperties,
                new ObjectMapper());
    }

    @Test
    @DisplayName("정상 분류 응답 파싱")
    void classify_validResponse_returnsCategory() {
        stubGemini(CLASSIFY_RESPONSE);

        Category result = provider.classify("경제 뉴스", "경제 관련 내용");

        assertThat(result).isEqualTo(Category.ECONOMY_FINANCE);
    }

    @Test
    @DisplayName("enum 외 카테고리 값 → OTHER 폴백 + WARN 로그")
    void classify_unknownCategory_fallbackToOther() {
        stubGemini(UNKNOWN_CATEGORY_RESPONSE);

        Category result = provider.classify("분류 불가 뉴스", "내용");

        assertThat(result).isEqualTo(Category.OTHER);
    }

    @Test
    @DisplayName("정상 요약 응답 파싱")
    void summarize_validResponse_returnsText() {
        stubGemini(SUMMARIZE_RESPONSE);

        String result = provider.summarize("제목", "내용", SummaryDepth.BALANCED);

        assertThat(result).isEqualTo("경제 뉴스 요약 내용입니다.");
    }

    @Test
    @DisplayName("HTTP 오류 → AiProviderException")
    void classify_httpError_throwsAiProviderException() {
        wireMock.stubFor(
                post(anyUrl())
                        .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.classify("제목", "내용"))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("HTTP 400 → AiProviderException")
    void classify_400Error_throwsAiProviderException() {
        wireMock.stubFor(
                post(anyUrl())
                        .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad request\"}")));

        assertThatThrownBy(() -> provider.classify("제목", "내용"))
                .isInstanceOf(AiProviderException.class);
    }

    private void stubGemini(String responseBody) {
        wireMock.stubFor(
                post(anyUrl())
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(responseBody)));
    }
}
