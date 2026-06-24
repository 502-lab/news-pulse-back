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
 * 크라운주얼 #5 — 콜드스타트 분기(010 US2, 실 PG, F3 반영). fallback은 조회·관심사 둘 다 0일 때만.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RecommendationColdStartIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_rec_cold_it")
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
    private long a1;
    private long a2;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, saved_articles, user_interests, articles,"
                    + " refresh_tokens, accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        // 추천 후보(최근 비숨김)
        a1 = article("c1", 1);
        a2 = article("c2", 2);
    }

    private UUID account(String email) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = ?", UUID.class, email);
    }

    private long article(String url, int daysAgo) {
        OffsetDateTime pub = OffsetDateTime.now().minusDays(daysAgo);
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, category, published_at,"
                    + " first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                    + " VALUES (?, ?, ?, 'IT', ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, url, pub, pub, pub.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    private String token(String email) {
        Map<?, ?> resp =
                client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", email, "password", PW))
                        .retrieve()
                        .body(Map.class);
        return (String) ((Map<?, ?>) ((Map<?, ?>) resp.get("data")).get("tokens")).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recommend(String email) {
        Map<?, ?> resp =
                client.get()
                        .uri("/api/v1/me/recommendations")
                        .header("Authorization", "Bearer " + token(email))
                        .retrieve()
                        .body(Map.class);
        return (Map<String, Object>) resp.get("data");
    }

    @Test
    @DisplayName("★ 조회0 AND 관심사0 → coldStart=true + 빈 목록 아님")
    void bothZero_coldStartFallback() {
        account("cold@cold.local");
        Map<String, Object> r = recommend("cold@cold.local");
        assertThat(r.get("coldStart")).isEqualTo(true);
        assertThat((List<?>) r.get("items")).isNotEmpty(); // 트렌드/최근 fallback
    }

    @Test
    @DisplayName("관심사有(조회0) → coldStart=false(관심사 기반)")
    void interestsOnly_notColdStart() {
        UUID acc = account("int@cold.local");
        jdbcTemplate.update(
                "INSERT INTO user_interests (account_id, category) VALUES (?, 'IT')", acc);
        Map<String, Object> r = recommend("int@cold.local");
        assertThat(r.get("coldStart")).isEqualTo(false);
        assertThat((List<?>) r.get("items")).isNotEmpty();
    }

    @Test
    @DisplayName("★ 조회有(관심사0) → coldStart=false(조회 프로파일 기반) — F3")
    void historyOnly_notColdStart() {
        UUID acc = account("hist@cold.local");
        // a1을 조회(이력 생성) — a2가 후보로 남음
        jdbcTemplate.update(
                "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)"
                        + " VALUES (?, ?, 'VIEW', 'SERVER', NOW())",
                acc, a1);
        Map<String, Object> r = recommend("hist@cold.local");
        assertThat(r.get("coldStart")).isEqualTo(false);
    }
}
