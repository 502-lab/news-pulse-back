package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 크라운주얼 #4 — 추천 제외(010 US2, 실 PG). 이미 조회·저장·숨김 기사는 추천 0건.
 * discriminating: 안 본·안 저장 기사는 추천에 포함(파이프라인 살아있음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RecommendationExclusionIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_rec_excl_it")
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
    private UUID acc;
    private String token;
    private long fresh;
    private long viewed;
    private long saved;
    private long hidden;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, saved_articles, article_keyword, trend_keyword_slot,"
                    + " articles, refresh_tokens, accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        acc = account("a@rec.local");
        token = login("a@rec.local");

        fresh = article("fresh", 1, false); // 후보 ✓
        viewed = article("viewed", 1, false);
        saved = article("saved", 1, false);
        hidden = article("hidden", 1, true);

        jdbcTemplate.update(
                "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)"
                        + " VALUES (?, ?, 'VIEW', 'SERVER', NOW())",
                acc, viewed);
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id) VALUES (?, ?)", acc, saved);
    }

    private UUID account(String email) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = ?", UUID.class, email);
    }

    private long article(String url, int daysAgo, boolean hiddenFlag) {
        OffsetDateTime pub = OffsetDateTime.now().minusDays(daysAgo);
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, category, published_at,"
                    + " first_collected_at, category_status, summary_status, expires_at, feed_visible,"
                    + " admin_hidden_at)"
                    + " VALUES (?, ?, ?, 'IT', ?, ?, 'COMPLETED', 'COMPLETED', ?, true, ?)",
                url, url, url, pub, pub, pub.plusDays(90), hiddenFlag ? OffsetDateTime.now() : null);
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
        return (String) ((Map<?, ?>) ((Map<?, ?>) resp.get("data")).get("tokens")).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recommendItems() {
        Map<?, ?> resp =
                client.get()
                        .uri("/api/v1/me/recommendations")
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(Map.class);
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return (List<Map<String, Object>>) data.get("items");
    }

    @Test
    @DisplayName("★ 조회·저장·숨김 기사 추천 0건 + 안 본 기사는 추천됨(파이프라인 살아있음)")
    void excludesViewedSavedHidden_keepsFresh() {
        List<Long> ids =
                recommendItems().stream()
                        .map(i -> ((Number) i.get("articleId")).longValue())
                        .toList();

        assertThat(ids).contains(fresh); // 파이프라인 살아있음(필터가 전부를 거르지 않음)
        assertThat(ids).doesNotContain(viewed, saved, hidden); // 제외 0건
    }
}
