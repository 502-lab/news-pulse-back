package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FeedIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_feed_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_HASH = encoder.encode("Admin@test123!");

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
        registry.add("email-service.base-url", wireMock::baseUrl);
        registry.add("email-service.api-key", () -> "test-api-key");
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@feed-it.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
        registry.add("oauth.apple.private-key", () -> "");
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;

    private RestClient restClient;
    private String userToken;
    private UUID userId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();

        wireMock.stubFor(post(urlPathEqualTo("/send-verification-code"))
                .willReturn(aResponse().withStatus(200)));

        // DB 초기화 (saved_articles 포함)
        jdbcTemplate.execute("DELETE FROM saved_articles");
        jdbcTemplate.execute("DELETE FROM summaries");
        jdbcTemplate.execute("DELETE FROM article_sources");
        jdbcTemplate.execute("DELETE FROM articles");
        jdbcTemplate.execute("DELETE FROM follow_keywords");
        jdbcTemplate.execute("DELETE FROM user_interests");
        jdbcTemplate.execute("DELETE FROM reading_preferences");
        jdbcTemplate.execute("DELETE FROM consent_records");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM accounts WHERE email != 'admin@feed-it.local'");

        userToken = registerAndLoginUser("feed-test@example.com");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'feed-test@example.com'",
                UUID.class);
    }

    // ── IT1: 랭킹 순서 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("관심사 ECONOMY_FINANCE 설정 시 해당 카테고리 기사가 먼저 반환 (랭킹 순서)")
    void getFeed_withInterests_rankedArticlesFirst() {
        // 사용자 관심사 설정
        jdbcTemplate.update(
                "INSERT INTO user_interests (id, account_id, category) VALUES (gen_random_uuid(), ?, ?)",
                userId, "ECONOMY_FINANCE");

        // 기사 2개 삽입: 같은 시각, 카테고리만 다름
        OffsetDateTime pubAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);
        Long econId = insertArticle("경제 뉴스", "ECONOMY_FINANCE", pubAt);
        Long politicsId = insertArticle("정치 뉴스", "POLITICS", pubAt);

        Map<?, ?> response = restClient.get()
                .uri("/api/v1/feed")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        assertThat(response).isNotNull();
        @SuppressWarnings("unchecked")
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertThat(data.get("personalized")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");
        assertThat(articles).isNotEmpty();
        assertThat(articles.get(0).get("id")).isEqualTo(econId.intValue());
    }

    // ── IT2: 관심사 없음 → personalized=false, 최신순 ──────────────────────

    @Test
    @DisplayName("관심사·키워드 없음 → personalized=false, 최신순 반환")
    void getFeed_noInterests_fallbackLatest() {
        OffsetDateTime older = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5);
        OffsetDateTime newer = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        Long oldId = insertArticle("오래된 뉴스", "POLITICS", older);
        Long newId = insertArticle("최신 뉴스", "IT", newer);

        Map<?, ?> response = restClient.get()
                .uri("/api/v1/feed")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertThat(data.get("personalized")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");
        assertThat(articles.get(0).get("id")).isEqualTo(newId.intValue());
        assertThat(articles.get(1).get("id")).isEqualTo(oldId.intValue());
    }

    // ── IT3: 커서 2-페이지 중복 없음 (AS1.6) ───────────────────────────────

    @Test
    @DisplayName("커서 2-페이지 연속 조회 시 중복 기사 없음 (AS1.6)")
    void getFeed_cursorPagination_noDuplicates() {
        // 기사 5개 삽입
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        for (int i = 0; i < 5; i++) {
            insertArticle("기사 " + i, "IT", base.minusMinutes(i * 10));
        }

        // Page 1: size=3
        Map<?, ?> page1 = restClient.get()
                .uri("/api/v1/feed?size=3")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<?, ?> page1Data = (Map<?, ?>) page1.get("data");
        assertThat(page1Data.get("hasNext")).isEqualTo(true);
        String nextCursor = (String) page1Data.get("nextCursor");
        assertThat(nextCursor).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> page1Articles = (List<Map<?, ?>>) page1Data.get("articles");
        Set<Object> page1Ids = page1Articles.stream().map(a -> a.get("id")).collect(java.util.stream.Collectors.toSet());

        // Page 2
        Map<?, ?> page2 = restClient.get()
                .uri("/api/v1/feed?size=3&cursor=" + nextCursor)
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<?, ?> page2Data = (Map<?, ?>) page2.get("data");
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> page2Articles = (List<Map<?, ?>>) page2Data.get("articles");
        Set<Object> page2Ids = page2Articles.stream().map(a -> a.get("id")).collect(java.util.stream.Collectors.toSet());

        // 중복 없음
        page2Ids.forEach(id -> assertThat(page1Ids).doesNotContain(id));
        // 전체 5개 커버
        assertThat(page1Ids.size() + page2Ids.size()).isEqualTo(5);
    }

    // ── IT4: 미인증 → 401 ──────────────────────────────────────────────────

    @Test
    @DisplayName("인증 없이 피드 요청 → 401")
    void getFeed_noToken_returns401() {
        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/api/v1/feed")
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("401");
    }

    // ── IT5: 이메일 미인증 → 403 ───────────────────────────────────────────

    @Test
    @DisplayName("이메일 미인증 계정으로 피드 요청 → 403")
    void getFeed_emailNotVerified_returns403() {
        String unverifiedToken = registerUnverifiedUser("unverified-feed@example.com");

        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/api/v1/feed")
                        .header("Authorization", "Bearer " + unverifiedToken)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("403");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String registerAndLoginUser(String email) {
        // 활성 약관 ID 조회 (signup body에 포함)
        List<Map<String, Object>> terms = jdbcTemplate.queryForList(
                "SELECT id FROM terms_versions WHERE is_active = true");
        List<Map<String, Object>> consents = terms.stream()
                .map(t -> Map.<String, Object>of("termsVersionId", t.get("id").toString(), "agreed", true))
                .toList();

        restClient.post()
                .uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Test@pass123!", "consents", consents, "ageConfirmed", true))
                .retrieve()
                .toBodilessEntity();

        // DB에서 직접 이메일 인증 완료 처리
        jdbcTemplate.update("UPDATE accounts SET email_verified = true WHERE email = ?", email);

        return loginUser(email);
    }

    private String loginUser(String email) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Test@pass123!"))
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) response.get("tokens");
        return (String) tokens.get("accessToken");
    }

    private String registerUnverifiedUser(String email) {
        List<Map<String, Object>> terms = jdbcTemplate.queryForList(
                "SELECT id FROM terms_versions WHERE is_active = true");
        List<Map<String, Object>> consents = terms.stream()
                .map(t -> Map.<String, Object>of("termsVersionId", t.get("id").toString(), "agreed", true))
                .toList();

        restClient.post()
                .uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Test@pass123!", "consents", consents, "ageConfirmed", true))
                .retrieve()
                .toBodilessEntity();

        // 이메일 인증 없이 바로 로그인 (이메일 미인증 상태)
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Test@pass123!"))
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) response.get("tokens");
        return (String) tokens.get("accessToken");
    }

    private Long insertArticle(String title, String category, OffsetDateTime publishedAt) {
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM sources LIMIT 1", Long.class);
        if (sourceId == null) {
            jdbcTemplate.update(
                    "INSERT INTO sources (name, feed_url, adapter_type) VALUES (?, ?, ?)",
                    "테스트 매체", "https://test.example.com/feed", "RSS");
            sourceId = jdbcTemplate.queryForObject("SELECT id FROM sources LIMIT 1", Long.class);
        }

        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, published_at, "
                        + "first_collected_at, category, category_status, summary_status, "
                        + "expires_at, feed_visible) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                "https://example.com/" + UUID.randomUUID(), "https://example.com/orig",
                title, publishedAt, publishedAt, category, publishedAt.plusDays(90));

        Long articleId = jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE title = ? ORDER BY id DESC LIMIT 1", Long.class, title);

        jdbcTemplate.update(
                "INSERT INTO article_sources (article_id, source_id, collected_at) VALUES (?, ?, ?)",
                articleId, sourceId, publishedAt);

        return articleId;
    }
}
