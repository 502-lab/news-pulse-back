package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * US3 Save Integration Tests — 6 crown-jewel assertions.
 * id 비교는 longValue() 통일 — int 캐스트 금지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SavedArticleIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_saved_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@saved-it.local");
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

        jdbcTemplate.execute("DELETE FROM summaries");
        jdbcTemplate.execute("DELETE FROM article_sources");
        jdbcTemplate.execute("DELETE FROM saved_articles");
        jdbcTemplate.execute("DELETE FROM articles WHERE title LIKE 'Bulk Article %' OR title LIKE 'Save Test %'");
        jdbcTemplate.execute("DELETE FROM follow_keywords");
        jdbcTemplate.execute("DELETE FROM user_interests");
        jdbcTemplate.execute("DELETE FROM reading_preferences");
        jdbcTemplate.execute("DELETE FROM consent_records");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM accounts WHERE email NOT LIKE 'admin@saved-it.local'");

        userToken = registerAndLoginUser("save-test@example.com");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'save-test@example.com'", UUID.class);
    }

    // ── [SIT1] 재저장 멱등: 같은 기사 2번 저장 → 200 + DB row 1개 ───────────────

    @Test
    @DisplayName("[SIT1] 재저장 멱등 — 2번 저장 → 200 + DB row 1개(중복 없음)")
    void save_twice_idempotent200_singleDbRow() {
        Long articleId = insertArticle("Save Test 재저장");

        // First save → 201
        restClient.post()
                .uri("/api/v1/articles/" + articleId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity();

        // Second save → 200 (idempotent)
        var response = restClient.post()
                .uri("/api/v1/articles/" + articleId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // DB: exactly 1 row
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saved_articles WHERE account_id = ? AND article_id = ?",
                Long.class, userId, articleId);
        assertThat(count).isEqualTo(1L);
    }

    // ── [SIT2] 미저장 기사 해제 → 204 (no-op 멱등) ──────────────────────────────

    @Test
    @DisplayName("[SIT2] 미저장 기사 해제 → 204 (no-op 멱등)")
    void unsave_notSaved_returns204NoOp() {
        Long articleId = insertArticle("Save Test 미저장 해제");
        // NOT saved

        var response = restClient.delete()
                .uri("/api/v1/articles/" + articleId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    // ── [SIT3] 상한: 1000건 채운 상태 1001번째 신규 저장 → 409 ──────────────────

    @Test
    @DisplayName("[SIT3] 1000건 상한 채운 상태 1001번째 신규 저장 → 409 SAVE_LIMIT_EXCEEDED")
    void save_overLimit_returns409() {
        // Insert 1000 dummy articles + save records via JDBC (fast bulk)
        List<Long> bulkIds = insertBulkArticles(1001);
        bulkSave(userId, bulkIds.subList(0, 1000));

        Long newArticleId = bulkIds.get(1000); // 1001st article, not yet saved

        assertThatThrownBy(() -> restClient.post()
                .uri("/api/v1/articles/" + newArticleId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getResponseBodyAsString()).contains("SAVE_LIMIT_EXCEEDED");
                });
    }

    // ── [SIT4] C2 엣지: 1000건 + 이미 저장된 기사 재저장 → 200(exists 단락, 409 아님) ─

    @Test
    @DisplayName("[SIT4] C2 엣지 — 1000건 상한 + 이미 저장된 기사 재저장 → 200(exists 단락)")
    void save_alreadySaved_atLimit_returns200NotConflict() {
        List<Long> bulkIds = insertBulkArticles(1000);
        Long alreadySavedId = bulkIds.get(0);
        // Save all 1000 (including alreadySavedId) via JDBC
        bulkSave(userId, bulkIds);

        // Re-save the already-saved article (exists check should short-circuit before limit check)
        var response = restClient.post()
                .uri("/api/v1/articles/" + alreadySavedId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(200); // not 409
    }

    // ── [SIT5] 미존재 articleId 저장 → 404 ARTICLE_NOT_FOUND ────────────────────

    @Test
    @DisplayName("[SIT5] 미존재 articleId → 404 ARTICLE_NOT_FOUND")
    void save_articleNotFound_returns404() {
        Long ghostId = 99999999L;

        assertThatThrownBy(() -> restClient.post()
                .uri("/api/v1/articles/" + ghostId + "/save")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                    assertThat(ex.getResponseBodyAsString()).contains("ARTICLE_NOT_FOUND");
                });
    }

    // ── [SIT6] 목록 savedAt DESC 정렬 + SavedArticleItem(savedAt) 필드 실제 반환 ─

    @Test
    @DisplayName("[SIT6] 목록 savedAt DESC 정렬 + SavedArticleItem에 savedAt 실제 포함(F2)")
    void list_savedAtDescOrder_andSavedAtPresentInResponse() {
        Long articleA = insertArticle("Save Test 기사 A");
        Long articleB = insertArticle("Save Test 기사 B");

        // Insert with explicit savedAt times: B saved later (t2 > t1)
        Instant t1 = Instant.now().minusSeconds(20);
        Instant t2 = Instant.now().minusSeconds(5);
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id, saved_at) VALUES (?, ?, ?)",
                userId, articleA, java.sql.Timestamp.from(t1));
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id, saved_at) VALUES (?, ?, ?)",
                userId, articleB, java.sql.Timestamp.from(t2));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/me/saved-articles")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");

        assertThat(articles).hasSize(2);

        // savedAt DESC: B (t2) comes first, A (t1) second
        // Response structure: SavedArticleItem { savedAt, article: { id, ... } }
        @SuppressWarnings("unchecked")
        Map<?, ?> firstArticleMap = (Map<?, ?>) articles.get(0).get("article");
        Long firstId = ((Number) firstArticleMap.get("id")).longValue();

        @SuppressWarnings("unchecked")
        Map<?, ?> secondArticleMap = (Map<?, ?>) articles.get(1).get("article");
        Long secondId = ((Number) secondArticleMap.get("id")).longValue();

        assertThat(firstId).isEqualTo(articleB);   // newer save first
        assertThat(secondId).isEqualTo(articleA);

        // savedAt field must be present (non-null) on each item
        assertThat(articles.get(0).get("savedAt")).isNotNull();
        assertThat(articles.get(1).get("savedAt")).isNotNull();
    }

    // ── [SIT7] 미인증 → 401 ───────────────────────────────────────────────────

    @Test
    @DisplayName("[SIT7] 미인증 저장 요청 → 401")
    void save_unauthenticated_returns401() {
        Long articleId = insertArticle("Save Test 미인증");

        assertThatThrownBy(() -> restClient.post()
                .uri("/api/v1/articles/" + articleId + "/save")
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(401));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String registerAndLoginUser(String email) {
        List<Map<String, Object>> terms = jdbcTemplate.queryForList(
                "SELECT id FROM terms_versions WHERE is_active = true");
        List<Map<String, Object>> consents = terms.stream()
                .map(t -> Map.<String, Object>of(
                        "termsVersionId", t.get("id").toString(), "agreed", true))
                .toList();

        restClient.post()
                .uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", email,
                        "password", "Test@pass123!",
                        "consents", consents,
                        "ageConfirmed", true))
                .retrieve().toBodilessEntity();

        jdbcTemplate.update(
                "UPDATE accounts SET email_verified = true WHERE email = ?", email);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Test@pass123!"))
                .retrieve().body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) response.get("tokens");
        return (String) tokens.get("accessToken");
    }

    private Long insertArticle(String title) {
        String url = "https://saved-it.example.com/" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, published_at, "
                        + "first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, title, now, now, now.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    /**
     * PostgreSQL generate_series로 count개 기사를 단일 쿼리로 삽입, ID 목록 반환.
     */
    private List<Long> insertBulkArticles(int count) {
        String prefix = "https://bulk-" + UUID.randomUUID() + "-";
        return jdbcTemplate.queryForList(
                "INSERT INTO articles (normalized_url, original_url, title, published_at, "
                        + "first_collected_at, category_status, summary_status, expires_at, feed_visible) "
                        + "SELECT '" + prefix + "' || gs, '" + prefix + "' || gs, "
                        + "'Bulk Article ' || gs, NOW(), NOW(), "
                        + "'COMPLETED', 'COMPLETED', NOW() + INTERVAL '90 days', false "
                        + "FROM generate_series(1, " + count + ") gs "
                        + "RETURNING id",
                Long.class);
    }

    private void bulkSave(UUID accountId, List<Long> articleIds) {
        List<Object[]> batchArgs = new ArrayList<>();
        for (Long id : articleIds) {
            batchArgs.add(new Object[]{accountId, id});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO saved_articles (account_id, article_id, saved_at) VALUES (?, ?, NOW())",
                batchArgs);
    }
}
