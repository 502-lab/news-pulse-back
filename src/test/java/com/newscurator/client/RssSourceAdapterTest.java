package com.newscurator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.client.source.ArticleCandidate;
import com.newscurator.client.source.RssSourceAdapter;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RssSourceAdapterTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().build();

    private RssSourceAdapter adapter;

    private static final String VALID_RSS =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test News</title>
                <link>https://example.com</link>
                <item>
                  <title>Breaking News</title>
                  <link>https://example.com/news/1</link>
                  <description>Test description</description>
                  <pubDate>Mon, 09 Jun 2026 10:00:00 +0000</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private static final String MALFORMED_RSS = "Not XML at all <broken>";

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.create();
        adapter = new RssSourceAdapter(restClient);
    }

    @Test
    @DisplayName("정상 RSS 피드 파싱 시 ArticleCandidate 반환")
    void fetchCandidates_validRss_returnsArticles() {
        wireMock.stubFor(
                get(urlPathEqualTo("/rss/news.xml"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/rss+xml")
                                        .withBody(VALID_RSS)));

        Source source = buildRssSource(wireMock.baseUrl() + "/rss/news.xml");
        List<ArticleCandidate> candidates = adapter.fetchCandidates(source);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).title()).isEqualTo("Breaking News");
        assertThat(candidates.get(0).url()).contains("example.com/news/1");
    }

    @Test
    @DisplayName("malformed XML 파싱 실패 시 RuntimeException 전파 → recordFailure 경로")
    void fetchCandidates_malformedXml_throwsException() {
        wireMock.stubFor(
                get(urlPathEqualTo("/rss/bad.xml"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/rss+xml")
                                        .withBody(MALFORMED_RSS)));

        Source source = buildRssSource(wireMock.baseUrl() + "/rss/bad.xml");

        assertThatThrownBy(() -> adapter.fetchCandidates(source))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("HTTP 오류 응답 시 RuntimeException 전파 → recordFailure 경로")
    void fetchCandidates_httpError_throwsException() {
        wireMock.stubFor(
                get(urlPathEqualTo("/rss/error.xml"))
                        .willReturn(aResponse().withStatus(503)));

        Source source = buildRssSource(wireMock.baseUrl() + "/rss/error.xml");

        assertThatThrownBy(() -> adapter.fetchCandidates(source))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("308 리다이렉트 follow 후 정상 RSS 수집 성공")
    void fetchCandidates_308redirect_followsAndReturnsArticles() {
        wireMock.stubFor(
                get(urlPathEqualTo("/rss/redirect"))
                        .willReturn(
                                aResponse()
                                        .withStatus(308)
                                        .withHeader("Location", wireMock.baseUrl() + "/rss/final.xml")));
        wireMock.stubFor(
                get(urlPathEqualTo("/rss/final.xml"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/rss+xml")
                                        .withBody(VALID_RSS)));

        RestClient redirectClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NORMAL)
                                .build()))
                .build();
        RssSourceAdapter adapterWithRedirect = new RssSourceAdapter(redirectClient);

        Source source = buildRssSource(wireMock.baseUrl() + "/rss/redirect");
        List<ArticleCandidate> candidates = adapterWithRedirect.fetchCandidates(source);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).title()).isEqualTo("Breaking News");
    }

    @Test
    @DisplayName("RssSourceAdapter는 RSS 어댑터 타입만 지원")
    void supports_rssType_returnsTrue() {
        assertThat(adapter.supports(SourceAdapterType.RSS)).isTrue();
        assertThat(adapter.supports(SourceAdapterType.NAVER)).isFalse();
    }

    private Source buildRssSource(String feedUrl) {
        return Source.builder()
                .name("Test RSS")
                .feedUrl(feedUrl)
                .adapterType(SourceAdapterType.RSS)
                .active(true)
                .callBudgetDaily(1000)
                .build();
    }
}
