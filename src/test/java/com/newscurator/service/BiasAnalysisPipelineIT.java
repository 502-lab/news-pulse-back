package com.newscurator.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import java.time.OffsetDateTime;
import java.util.Map;
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

/**
 * T018: 편향 분석 파이프라인 통합 테스트 (Testcontainers 실 PostgreSQL + Gemini WireMock).
 * 수집→PENDING→DONE end-to-end, backfill ON CONFLICT 멱등 + rate-safe 드레인.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BiasAnalysisPipelineIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_bias_pipeline_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.ai.delay-between-calls-ms", () -> "0");
        registry.add("app.scheduler.bias.batch-size", () -> "10");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", wireMock::baseUrl);
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", wireMock::baseUrl);
    }

    @Autowired private BiasAnalysisService biasAnalysisService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private BiasAnalysisRepository biasAnalysisRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, summaries, article_sources, source_daily_usage,"
                        + " articles, sources RESTART IDENTITY CASCADE");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String geminiJson(String innerText) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "candidates",
                    java.util.List.of(Map.of(
                            "content",
                            Map.of("parts", java.util.List.of(Map.of("text", innerText)))))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubSuccess() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson(
                                "{\"score\": 20, \"keywords\": [\"k1\", \"k2\"]}"))));
    }

    private long insertArticle(String url) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build());
        return a.getId();
    }

    private long countByStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bias_analysis WHERE status = ?", Long.class, status);
    }

    @Test
    @DisplayName("PENDING → processBatch → DONE end-to-end")
    void pendingToDone() {
        long id = insertArticle("https://ex.com/e2e");
        stubSuccess();
        biasAnalysisService.createPendingForArticle(id);
        assertThat(countByStatus("PENDING")).isEqualTo(1);

        biasAnalysisService.processBatch();

        var row = jdbcTemplate.queryForMap(
                "SELECT status, value FROM bias_analysis WHERE article_id = ?", id);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("value")).isEqualTo(20);
    }

    @Test
    @DisplayName("backfill 멱등: 1회 created=N, 2회 created=0")
    void backfillIdempotent() {
        insertArticle("https://ex.com/b1");
        insertArticle("https://ex.com/b2");

        long first = biasAnalysisService.backfill();
        long second = biasAnalysisService.backfill();

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        assertThat(countByStatus("PENDING")).isEqualTo(2);
    }

    @Test
    @DisplayName("rate-safe 드레인: backfill 25건을 batch-size(10)씩 반복 processBatch로 전량 소비")
    void rateSafeDrain() {
        for (int i = 0; i < 25; i++) {
            insertArticle("https://ex.com/drain/" + i);
        }
        long created = biasAnalysisService.backfill();
        assertThat(created).isEqualTo(25);
        stubSuccess();

        // 한 번에 batch-size(10)씩만 소비 → 3회 호출로 전량 드레인
        biasAnalysisService.processBatch();
        assertThat(countByStatus("DONE")).isEqualTo(10);
        biasAnalysisService.processBatch();
        assertThat(countByStatus("DONE")).isEqualTo(20);
        biasAnalysisService.processBatch();

        assertThat(countByStatus("DONE")).isEqualTo(25);
        assertThat(countByStatus("PENDING")).isZero();
    }
}
