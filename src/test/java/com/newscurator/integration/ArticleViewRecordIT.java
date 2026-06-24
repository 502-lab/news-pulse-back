package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
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
 * 크라운주얼 T012 — 조회 기록 + 디바운스 + forward-seam(009 FR-001·004·005·009). 상세 조회 시 article_event
 * 1건(VIEW·SERVER·metric_value=null), 같은 (account,article) 30분내 재조회는 1건, 30분 경과 후 2건, 다른 기사 독립.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ArticleViewRecordIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_read_record_it")
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
    private String token;
    private long article1;
    private long article2;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, refresh_tokens, summaries, article_sources, articles,"
                        + " accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                "reader@record.local", ENCODER.encode(PW));
        article1 = insertArticle("https://record.local/1", "기사1");
        article2 = insertArticle("https://record.local/2", "기사2");
        token = login();
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

    private String login() {
        Map<?, ?> resp =
                client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "reader@record.local", "password", PW))
                        .retrieve()
                        .body(Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        return (String) ((Map<?, ?>) data.get("tokens")).get("accessToken");
    }

    private void getDetail(long articleId) {
        client.get()
                .uri("/api/v1/articles/" + articleId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private long eventCount(long articleId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_event WHERE article_id = ?", Long.class, articleId);
    }

    @Test
    @DisplayName("★ 조회 기록 1건(VIEW·SERVER·null) + 30분 디바운스 + 다른 기사 독립")
    void recordsViewWithDebounceAndForwardSeam() {
        // 1회차 → 1건 기록
        getDetail(article1);
        assertThat(eventCount(article1)).isEqualTo(1L);

        // forward-seam: VIEW·SERVER·metric_value=null만 기록
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT event_type, source, metric_value FROM article_event WHERE article_id = ?",
                        article1);
        assertThat(row.get("event_type")).isEqualTo("VIEW");
        assertThat(row.get("source")).isEqualTo("SERVER");
        assertThat(row.get("metric_value")).isNull();

        // 30분내 재조회 → 디바운스(여전히 1건)
        getDetail(article1);
        assertThat(eventCount(article1)).isEqualTo(1L);

        // 기존 행을 31분 전으로 backdate → 윈도우 밖 → 재조회 시 2건
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '31 minutes' WHERE article_id = ?",
                article1);
        getDetail(article1);
        assertThat(eventCount(article1)).isEqualTo(2L);

        // 다른 기사는 독립적으로 1건
        getDetail(article2);
        assertThat(eventCount(article2)).isEqualTo(1L);
    }
}
