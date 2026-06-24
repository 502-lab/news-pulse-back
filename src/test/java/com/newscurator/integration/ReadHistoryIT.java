package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 T018 — 읽은수 distinct + 이력 역순 + 같은 기사 1건 + 본인 스코프(009 US2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ReadHistoryIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_read_history_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", () -> "http://localhost:9999");
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", () -> "http://localhost:9999");
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;

    private RestClient client;
    private String tokenA;
    private String tokenB;
    private long article1;
    private long article2;
    private long article3;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, refresh_tokens, summaries, article_sources, articles,"
                        + " accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        seedAccount("a@history.local");
        seedAccount("b@history.local");
        article1 = insertArticle("https://history.local/1", "기사1");
        article2 = insertArticle("https://history.local/2", "기사2");
        article3 = insertArticle("https://history.local/3", "기사3");
        tokenA = login("a@history.local");
        tokenB = login("b@history.local");
    }

    private void seedAccount(String email) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW));
    }

    private long insertArticle(String url, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, published_at,"
                        + " first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, title, now, now, now.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    private String login(String email) {
        Map<?, ?> resp =
                client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", email, "password", PW))
                        .retrieve()
                        .body(Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        return (String) ((Map<?, ?>) data.get("tokens")).get("accessToken");
    }

    private void view(String token, long articleId) {
        client.get()
                .uri("/api/v1/articles/" + articleId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(String token, String path) {
        Map<?, ?> resp =
                client.get()
                        .uri(path)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(Map.class);
        return (Map<String, Object>) resp.get("data");
    }

    @Test
    @DisplayName("★ 읽은수 distinct + 이력 역순·같은기사 1건 + 본인 스코프")
    void readCountDistinct_historyDesc_ownScope() {
        // A: 기사1·2·3 조회. 기사1은 재조회(같은 기사 2번) — 디바운스 우회 위해 backdate 후 재조회.
        view(tokenA, article1);
        view(tokenA, article2);
        view(tokenA, article3);
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '31 minutes' WHERE article_id = ?",
                article1);
        view(tokenA, article1); // 같은 기사 2번째 조회(이력은 1건으로 dedup, 가장 최신)

        // 읽은수 = distinct 3 (기사1 중복 조회여도 1)
        Map<String, Object> count = getData(tokenA, "/api/v1/me/read-count");
        assertThat(((Number) count.get("readCount")).longValue()).isEqualTo(3L);

        // 이력 = 3건(같은 기사 1건씩), 최신순: 기사1(재조회) 먼저
        Map<String, Object> history = getData(tokenA, "/api/v1/me/read-history");
        List<Map<String, Object>> items = (List<Map<String, Object>>) history.get("items");
        assertThat(items).hasSize(3);
        assertThat(((Number) items.get(0).get("articleId")).longValue()).isEqualTo(article1);
        // 기사1이 이력에 1번만(중복 제거)
        long article1Count =
                items.stream()
                        .filter(i -> ((Number) i.get("articleId")).longValue() == article1)
                        .count();
        assertThat(article1Count).isEqualTo(1L);

        // 본인 스코프: B는 아무것도 안 봤으므로 0 + 이력 빈 목록
        Map<String, Object> countB = getData(tokenB, "/api/v1/me/read-count");
        assertThat(((Number) countB.get("readCount")).longValue()).isEqualTo(0L);
        Map<String, Object> historyB = getData(tokenB, "/api/v1/me/read-history");
        assertThat((List<?>) historyB.get("items")).isEmpty();
    }
}
