package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
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
 * 크라운주얼 #3 — 표본<5 처리(010 D3). 읽은 고유 기사 < 5 → sampleSufficient=false·분포 null·카운트 정상
 * (NPE·분모0 없음). 읽은 기사 0 사용자도 안전.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class InsightSampleThresholdIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_insight_sample_it")
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
    private UUID accFew;
    private String tokenFew;
    private String tokenZero;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, saved_articles, articles, refresh_tokens, accounts"
                        + " RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        accFew = account("few@sample.local");
        account("zero@sample.local");
        tokenFew = login("few@sample.local");
        tokenZero = login("zero@sample.local");

        // few: 3 기사만 조회(<5) + 1 북마크
        for (int i = 1; i <= 3; i++) {
            long id = article("u" + i, "기사" + i);
            view(accFew, id);
            if (i == 1) {
                jdbcTemplate.update(
                        "INSERT INTO saved_articles (account_id, article_id) VALUES (?, ?)", accFew, id);
            }
        }
    }

    private UUID account(String email) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = ?", UUID.class, email);
    }

    private long article(String url, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, category, published_at,"
                    + " first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                    + " VALUES (?, ?, ?, 'IT', ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, title, now, now, now.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    private void view(UUID acc, long articleId) {
        jdbcTemplate.update(
                "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)"
                        + " VALUES (?, ?, 'VIEW', 'SERVER', NOW())",
                acc, articleId);
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
    private Map<String, Object> insights(String token) {
        Map<?, ?> resp =
                client.get()
                        .uri("/api/v1/me/insights")
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(Map.class);
        return (Map<String, Object>) resp.get("data");
    }

    @Test
    @DisplayName("★ 읽은 기사 3(<5) → sampleSufficient=false·분포 null·카운트 정상")
    void belowThreshold_distributionsNull_countsReturned() {
        Map<String, Object> r = insights(tokenFew);
        assertThat(((Number) r.get("readCount")).longValue()).isEqualTo(3L);
        assertThat(((Number) r.get("bookmarkCount")).longValue()).isEqualTo(1L);
        assertThat(r.get("sampleSufficient")).isEqualTo(false);
        assertThat(r.get("topCategory")).isNull();
        assertThat(r.get("categoryDistribution")).isNull();
        assertThat(r.get("keywordDistribution")).isNull();
        assertThat(r.get("topOutlets")).isNull();
        assertThat(r.get("biasDistribution")).isNull();
    }

    @Test
    @DisplayName("읽은 기사 0 → 카운트 0·분포 null, 오류 없음(NPE·분모0 없음)")
    void zeroData_safe() {
        Map<String, Object> r = insights(tokenZero);
        assertThat(((Number) r.get("readCount")).longValue()).isZero();
        assertThat(((Number) r.get("bookmarkCount")).longValue()).isZero();
        assertThat(r.get("sampleSufficient")).isEqualTo(false);
        assertThat(r.get("categoryDistribution")).isNull();
    }
}
