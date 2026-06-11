package com.newscurator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.client.source.ArticleCandidate;
import com.newscurator.client.source.NaverSourceAdapter;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

class NaverSourceAdapterTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private NaverSourceAdapter adapter;

    private static final String NAVER_RESPONSE =
            """
            {
              "lastBuildDate": "Mon, 09 Jun 2026 10:00:00 +0900",
              "total": 1,
              "start": 1,
              "display": 1,
              "items": [
                {
                  "title": "경제 뉴스 <b>제목</b>",
                  "originallink": "https://example.com/news/economy/1",
                  "link": "https://news.naver.com/redirect/1",
                  "description": "경제 뉴스 요약",
                  "pubDate": "Mon, 09 Jun 2026 09:00:00 +0900"
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.create();
        adapter = new NaverSourceAdapter(restClient, wireMock.baseUrl(), "test-client-id", "test-secret");
    }

    @Test
    @DisplayName("정상 네이버 검색 API 응답 파싱")
    void fetchCandidates_validResponse_returnsArticles() {
        wireMock.stubFor(
                get(urlPathEqualTo("/v1/search/news.json"))
                        .withHeader("X-Naver-Client-Id", equalTo("test-client-id"))
                        .withHeader("X-Naver-Client-Secret", equalTo("test-secret"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(NAVER_RESPONSE)));

        Source source = buildNaverSource("경제 뉴스");
        List<ArticleCandidate> candidates = adapter.fetchCandidates(source);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).title()).contains("경제 뉴스");
        // originallink를 URL로 사용
        assertThat(candidates.get(0).url()).contains("example.com/news/economy/1");
    }

    @Test
    @DisplayName("인증 헤더가 요청에 포함됨")
    void fetchCandidates_includesAuthHeaders() {
        wireMock.stubFor(
                get(urlPathEqualTo("/v1/search/news.json"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(NAVER_RESPONSE)));

        Source source = buildNaverSource("경제");
        adapter.fetchCandidates(source);

        wireMock.verify(
                getRequestedFor(urlPathEqualTo("/v1/search/news.json"))
                        .withHeader("X-Naver-Client-Id", equalTo("test-client-id")));
    }

    @Test
    @DisplayName("HTTP 오류 시 RuntimeException 전파 → recordFailure 경로")
    void fetchCandidates_httpError_throwsException() {
        wireMock.stubFor(
                get(urlPathEqualTo("/v1/search/news.json"))
                        .willReturn(aResponse().withStatus(429)));

        Source source = buildNaverSource("경제");

        assertThatThrownBy(() -> adapter.fetchCandidates(source))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("NaverSourceAdapter는 NAVER 타입만 지원")
    void supports_naverType_returnsTrue() {
        assertThat(adapter.supports(SourceAdapterType.NAVER)).isTrue();
        assertThat(adapter.supports(SourceAdapterType.RSS)).isFalse();
    }

    private Source buildNaverSource(String query) {
        return Source.builder()
                .name("네이버-경제")
                .feedUrl(query)
                .adapterType(SourceAdapterType.NAVER)
                .active(true)
                .collectionIntervalMinutes(30)
                .callBudgetDaily(100)
                .build();
    }
}
