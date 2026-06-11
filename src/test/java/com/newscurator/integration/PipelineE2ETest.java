package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.Article;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SourceAdapterType;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SourceRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.AiProcessingService;
import com.newscurator.service.CollectionService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.client.RestClient;

/**
 * E2E 파이프라인 통합 테스트.
 * 수집 → AI처리 → 피드 API 전체 흐름 검증.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PipelineE2ETest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_e2e")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_PASSWORD = "Admin@test123!";
    private static final String ADMIN_HASH = encoder.encode(ADMIN_PASSWORD);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.ai.delay-between-calls-ms", () -> "0");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", wireMock::baseUrl);
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", wireMock::baseUrl);
        registry.add("email-service.base-url", wireMock::baseUrl);
        registry.add("email-service.api-key", () -> "test-api-key");
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@test.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
    }

    @LocalServerPort int port;

    @Autowired private ArticleRepository articleRepository;
    @Autowired private SummaryRepository summaryRepository;
    @Autowired private CollectionService collectionService;
    @Autowired private AiProcessingService aiProcessingService;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private RestClient restClient;
    private String adminAccessToken;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
        jdbcTemplate.execute(
                "TRUNCATE TABLE summaries, article_sources, source_daily_usage, articles, sources"
                        + " RESTART IDENTITY CASCADE");

        // All endpoints require auth (002 spec). Login as the Flyway-seeded admin.
        wireMock.stubFor(post(urlPathEqualTo("/send-verification-code"))
                .willReturn(aResponse().withStatus(200)));
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin@test.local", "password", ADMIN_PASSWORD))
                .retrieve()
                .body(Map.class);
        adminAccessToken = (String) ((Map<?, ?>) loginResp.get("tokens")).get("accessToken");
    }

    @Test
    @DisplayName("E2E: 피드 API 정상 응답 (빈 피드)")
    void e2e_feedApi_returnsEmptyFeed() {
        ResponseEntity<String> response =
                restClient.get().uri("/api/v1/articles")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("data");
    }

    @Test
    @DisplayName("E2E: 존재하지 않는 기사 404 응답")
    void e2e_articleNotFound_returns404() {
        try {
            restClient.get().uri("/api/v1/articles/999999")
                    .header("Authorization", "Bearer " + adminAccessToken)
                    .retrieve().toEntity(String.class);
            fail("Expected 404 exception");
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("E2E: RSS 수집 → AI 처리 → 피드 노출, brief 채워짐, 3 슬롯")
    void e2e_collectThenAi_feedShowsCompleted() {
        // RSS 스텁
        String rssBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>E2E Test Feed</title>
                    <link>https://e2e.example.com</link>
                    <description>E2E</description>
                    <item>
                      <title>E2E 테스트 기사 제목</title>
                      <link>https://e2e.example.com/news/1</link>
                      <description>E2E 기사 내용입니다.</description>
                      <pubDate>Mon, 09 Jun 2025 10:00:00 +0900</pubDate>
                    </item>
                  </channel>
                </rss>
                """;
        wireMock.stubFor(get(urlPathEqualTo("/rss/e2e.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(rssBody)));

        // Gemini 스텁
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("분류하세요"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("ECONOMY_FINANCE"))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("균형"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson(
                                "E2E 균형 요약 내용입니다. 충분히 긴 내용으로 200자 brief 트런케이션을"
                                        + " 검증합니다. 기사의 경제 관련 핵심 사항을 균형 있게 요약하여 독자들이"
                                        + " 이해하기 쉽게 작성되었습니다."))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("심층"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("E2E 심층 분석 요약입니다."))));

        // 수집
        sourceRepository.save(Source.builder()
                .name("E2E Test RSS")
                .feedUrl(wireMock.baseUrl() + "/rss/e2e.xml")
                .adapterType(SourceAdapterType.RSS)
                .active(true)
                .collectionIntervalMinutes(60)
                .callBudgetDaily(1000)
                .build());
        collectionService.collectAll();

        assertThat(articleRepository.count()).isEqualTo(1);
        Article article = articleRepository.findAll().get(0);
        assertThat(article.getCategoryStatus()).isEqualTo(ProcessingStatus.PENDING);

        // AI 처리
        aiProcessingService.processArticle(article);

        Article processed = articleRepository.findById(article.getId()).orElseThrow();
        assertThat(processed.getCategoryStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(processed.getSummaryStatus()).isEqualTo(ProcessingStatus.COMPLETED);

        // 피드 API 확인
        ResponseEntity<String> feedResponse =
                restClient.get().uri("/api/v1/articles")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .retrieve().toEntity(String.class);
        assertThat(feedResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(feedResponse.getBody()).contains("ECONOMY_FINANCE");

        // 상세 API 확인 (DEEP 슬롯 lazy 생성 포함)
        ResponseEntity<String> detailResponse = restClient.get()
                .uri("/api/v1/articles/" + article.getId())
                .header("Authorization", "Bearer " + adminAccessToken)
                .retrieve()
                .toEntity(String.class);
        assertThat(detailResponse.getStatusCode().is2xxSuccessful()).isTrue();
        String body = detailResponse.getBody();
        assertThat(body).contains("COMPLETED");            // BALANCED/BRIEF/DEEP 슬롯 상태
        assertThat(body).contains("E2E 균형 요약 내용");   // balanced content
        assertThat(body).contains("E2E 심층 분석");        // deep content (lazy generated)
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private static String geminiJson(String text) {
        return """
                {"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}
                """.formatted(text.replace("\"", "\\\""));
    }
}
