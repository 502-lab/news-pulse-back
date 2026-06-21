package com.newscurator.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
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
 * T044: one-shot 복구 전용 통합 테스트 (Testcontainers 실 PostgreSQL).
 *
 * <p>lease 회수와 별개 경로 — FAILED·attempt_count=3·failed_at+6h 게이트 기반.
 * 6h 전 미발화 / 6h 후 1회 발화 / 성공→DONE(attempt 유지) / 실패→terminal(attempt=4, 재발화 0).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BiasOneShotRecoveryIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_bias_oneshot_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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
    }

    @Autowired private BiasAnalysisService biasAnalysisService;
    @Autowired private ArticleRepository articleRepository;
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
                                "{\"score\": -10, \"keywords\": [\"k1\", \"k2\"]}"))));
    }

    private void stubHardFailure() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(400)));
    }

    private long insertFailedArticle(String url, OffsetDateTime failedAt) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build());
        jdbcTemplate.update("""
                INSERT INTO bias_analysis
                    (article_id, status, attempt_count, next_retry_at, failed_at, created_at, updated_at)
                VALUES (?, 'FAILED', 3, NOW(), ?, NOW(), NOW())
                """, a.getId(), failedAt);
        return a.getId();
    }

    private Map<String, Object> row(long articleId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, value, attempt_count FROM bias_analysis WHERE article_id = ?",
                articleId);
    }

    @Test
    @DisplayName("6h 미경과: 복구 미발화 — 여전히 FAILED·attempt=3·value null")
    void beforeSixHours_notFired() {
        stubSuccess();
        long id = insertFailedArticle("https://ex.com/a", OffsetDateTime.now().minusHours(1));

        biasAnalysisService.recoverOneShotFailed();

        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("FAILED");
        assertThat(r.get("attempt_count")).isEqualTo(3);
        assertThat(r.get("value")).isNull();
        wireMock.verify(exactly(0), postRequestedFor(urlPathMatching(".*generateContent.*")));
    }

    @Test
    @DisplayName("6h 경과 + 성공: completeOneShot → DONE, attempt_count=3 유지")
    void afterSixHours_success_done() {
        stubSuccess();
        long id = insertFailedArticle("https://ex.com/b", OffsetDateTime.now().minusHours(7));

        biasAnalysisService.recoverOneShotFailed();

        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("DONE");
        assertThat(r.get("attempt_count")).isEqualTo(3);
        assertThat(r.get("value")).isEqualTo(-10);
    }

    @Test
    @DisplayName("6h 경과 + 실패: failTerminal → attempt=4 terminal FAILED, 재발화 0")
    void afterSixHours_failure_terminalNoRefire() {
        stubHardFailure();
        long id = insertFailedArticle("https://ex.com/c", OffsetDateTime.now().minusHours(7));

        // 1회차 복구 시도 → 실패 → terminal
        biasAnalysisService.recoverOneShotFailed();

        Map<String, Object> after1 = row(id);
        assertThat(after1.get("status")).isEqualTo("FAILED");
        assertThat(after1.get("attempt_count")).isEqualTo(4);
        assertThat(after1.get("value")).isNull();

        int callsAfterFirst =
                wireMock.findAll(postRequestedFor(urlPathMatching(".*generateContent.*"))).size();

        // 2회차: attempt_count=4 이므로 recovery 술어(attempt_count=3)에서 제외 → 재발화 없음
        biasAnalysisService.recoverOneShotFailed();

        Map<String, Object> after2 = row(id);
        assertThat(after2.get("attempt_count")).isEqualTo(4);
        assertThat(after2.get("status")).isEqualTo("FAILED");
        wireMock.verify(exactly(callsAfterFirst),
                postRequestedFor(urlPathMatching(".*generateContent.*")));
    }
}
