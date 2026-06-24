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
 * 크라운주얼 #2 — 6항목 집계·본인스코프·숨김제외·편향버킷(010 US1, 실 PG).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class InsightAggregationIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_insight_agg_it")
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
    private java.util.UUID accA;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, saved_articles, article_keyword, bias_analysis,"
                    + " article_sources, summaries, articles, sources, refresh_tokens, accounts"
                    + " RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        accA = seedAccount("a@insight.local");
        seedAccount("b@insight.local");
        tokenA = login("a@insight.local");
        tokenB = login("b@insight.local");

        long s1 = source("연합뉴스");
        long s2 = source("조선일보");
        // 읽은 기사 5건(TECH 3·POLITICS 1·ECONOMY 1) + 숨김 1건
        long a1 = article("u1", "AI 뉴스", "IT", s1, false);
        long a2 = article("u2", "AI 반도체", "IT", s1, false);
        long a3 = article("u3", "선거 뉴스", "POLITICS", s2, false);
        long a4 = article("u4", "TECH 동향", "IT", s2, false);
        long a5 = article("u5", "경제 지표", "ECONOMY_FINANCE", s1, false);
        long aHidden = article("u6", "숨김 기사", "IT", s1, true); // admin_hidden

        for (long id : List.of(a1, a2, a3, a4, a5, aHidden)) {
            view(accA, id);
        }
        // 편향: a1=-50(진보)·a2=0(중립)·a3=50(보수)·a4=-40(진보) DONE, a5 PENDING(제외)
        bias(a1, -50, "DONE");
        bias(a2, 0, "DONE");
        bias(a3, 50, "DONE");
        bias(a4, -40, "DONE");
        bias(a5, null, "PENDING");
        keyword(a1, "AI");
        keyword(a2, "AI");
        keyword(a3, "선거");
        // 북마크 1건
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id) VALUES (?, ?)", accA, a1);
    }

    private java.util.UUID seedAccount(String email) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = ?", java.util.UUID.class, email);
    }

    private long source(String name) {
        jdbcTemplate.update(
                "INSERT INTO sources (name, feed_url, adapter_type, active) VALUES (?, ?, 'RSS', true)",
                name, "https://" + name + ".local/feed");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sources WHERE name = ?", Long.class, name);
    }

    private long article(String url, String title, String category, long sourceId, boolean hidden) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, category, published_at,"
                    + " first_collected_at, category_status, summary_status, expires_at, feed_visible,"
                    + " admin_hidden_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true, ?)",
                url, url, title, category, now, now, now.plusDays(90), hidden ? now : null);
        long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
        jdbcTemplate.update(
                "INSERT INTO article_sources (article_id, source_id, collected_at) VALUES (?, ?, NOW())",
                id, sourceId);
        return id;
    }

    private void view(java.util.UUID acc, long articleId) {
        jdbcTemplate.update(
                "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)"
                        + " VALUES (?, ?, 'VIEW', 'SERVER', NOW())",
                acc, articleId);
    }

    private void bias(long articleId, Integer value, String status) {
        jdbcTemplate.update(
                "INSERT INTO bias_analysis (article_id, status, value, next_retry_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), NOW())",
                articleId, status, value);
    }

    private void keyword(long articleId, String term) {
        jdbcTemplate.update(
                "INSERT INTO article_keyword (article_id, term) VALUES (?, ?)", articleId, term);
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
    @DisplayName("★ 6항목 집계 — 읽은수·북마크·최다카테고리·편향버킷 + 숨김제외 + 본인스코프")
    void aggregates6Items_excludesHidden_ownScope() {
        Map<String, Object> a = insights(tokenA);

        // 읽은수 = 5 (숨김 a6 제외), 북마크 1
        assertThat(((Number) a.get("readCount")).longValue()).isEqualTo(5L);
        assertThat(((Number) a.get("bookmarkCount")).longValue()).isEqualTo(1L);
        assertThat(a.get("sampleSufficient")).isEqualTo(true);
        // 최다 카테고리 = TECH(3)
        assertThat(a.get("topCategory")).isEqualTo("IT");

        // 편향 분포: DONE 4건(진보2·중립1·보수1), a5 PENDING 제외
        Map<String, Object> bias = (Map<String, Object>) a.get("biasDistribution");
        assertThat(((Number) bias.get("total")).longValue()).isEqualTo(4L);
        assertThat(((Number) bias.get("liberalPercent")).doubleValue()).isEqualTo(50.0); // 2/4
        assertThat(((Number) bias.get("neutralPercent")).doubleValue()).isEqualTo(25.0); // 1/4
        assertThat(((Number) bias.get("conservativePercent")).doubleValue()).isEqualTo(25.0); // 1/4

        // 본인 스코프: B는 읽은 기사 0
        Map<String, Object> b = insights(tokenB);
        assertThat(((Number) b.get("readCount")).longValue()).isEqualTo(0L);
        assertThat(b.get("sampleSufficient")).isEqualTo(false);
    }
}
