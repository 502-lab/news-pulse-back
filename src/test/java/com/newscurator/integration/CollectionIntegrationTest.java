package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.Article;
import com.newscurator.domain.ArticleSource;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SourceAdapterType;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.SourceRepository;
import com.newscurator.service.CollectionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CollectionIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", wireMock::baseUrl);
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", wireMock::baseUrl);
    }

    @Autowired private CollectionService collectionService;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleSourceRepository articleSourceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE summaries, article_sources, source_daily_usage, articles, sources"
                        + " RESTART IDENTITY CASCADE");
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private Source rssSource(String path) {
        return sourceRepository.save(Source.builder()
                .name("Test-" + path)
                .feedUrl(wireMock.baseUrl() + path)
                .adapterType(SourceAdapterType.RSS)
                .active(true)
                .collectionIntervalMinutes(60)
                .callBudgetDaily(1000)
                .build());
    }

    private static final String VALID_RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://test.example.com</link>
                <description>Test</description>
                <item>
                  <title>Test Article Title</title>
                  <link>https://test.example.com/articles/1</link>
                  <description>Test description</description>
                  <pubDate>Mon, 09 Jun 2025 10:00:00 +0900</pubDate>
                </item>
              </channel>
            </rss>
            """;

    // ────────────────────────────── tests ──────────────────────────────

    @Test
    @DisplayName("정상 RSS 피드 수집 시 기사 1건 저장, PENDING 상태")
    void collect_validRss_persistsArticle() {
        wireMock.stubFor(get(urlPathEqualTo("/rss/valid.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(VALID_RSS)));
        Source source = rssSource("/rss/valid.xml");

        collectionService.collectAll();

        assertThat(articleRepository.count()).isEqualTo(1);
        Article article = articleRepository.findAll().get(0);
        assertThat(article.getNormalizedUrl()).isNotBlank();
        assertThat(article.getCategoryStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(article.getSummaryStatus()).isEqualTo(ProcessingStatus.PENDING);
        Source updated = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(updated.getConsecutiveFailureCount()).isZero();
    }

    @Test
    @DisplayName("HTTP 308 리다이렉트 시 최종 URL까지 따라가 기사 적재")
    void collect_308redirect_followsAndPersists() {
        wireMock.stubFor(get(urlPathEqualTo("/rss/redirect"))
                .willReturn(aResponse()
                        .withStatus(308)
                        .withHeader("Location", wireMock.baseUrl() + "/rss/final.xml")));
        wireMock.stubFor(get(urlPathEqualTo("/rss/final.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(VALID_RSS)));
        rssSource("/rss/redirect");

        collectionService.collectAll();

        assertThat(articleRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("파싱 불가 응답 시 기사 없음 + consecutive_failure_count 1 증가")
    void collect_brokenXml_recordsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/rss/broken.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>Not RSS</html>")));
        Source source = rssSource("/rss/broken.xml");

        collectionService.collectAll();

        assertThat(articleRepository.count()).isZero();
        Source updated = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(updated.getConsecutiveFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 출처가 동일 URL 수집 시 articles=1, article_sources=2 (is_merge=true 포함)")
    void collect_crossSourceDuplicate_mergesArticleSource() {
        wireMock.stubFor(get(urlPathEqualTo("/rss/source1.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(VALID_RSS)));
        wireMock.stubFor(get(urlPathEqualTo("/rss/source2.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(VALID_RSS)));
        rssSource("/rss/source1.xml");
        rssSource("/rss/source2.xml");

        collectionService.collectAll();

        assertThat(articleRepository.count()).isEqualTo(1);
        List<ArticleSource> articleSources = articleSourceRepository.findAll();
        assertThat(articleSources).hasSize(2);
        assertThat(articleSources.stream().filter(s -> !s.isMerge()).count()).isEqualTo(1);
        assertThat(articleSources.stream().filter(ArticleSource::isMerge).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 출처 재수집 시 article_sources 중복 생성 없음")
    void collect_sameSourceReCollect_noduplicateArticleSource() {
        wireMock.stubFor(get(urlPathEqualTo("/rss/same.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(VALID_RSS)));
        rssSource("/rss/same.xml");

        collectionService.collectAll();
        collectionService.collectAll();

        assertThat(articleRepository.count()).isEqualTo(1);
        assertThat(articleSourceRepository.count()).isEqualTo(1);
    }
}
