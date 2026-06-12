package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * US2 Search Integration Tests — 7 crown-jewel assertions.
 * Uses BigmPostgresImage (pg_bigm required for V9 GIN indexes).
 * id 비교는 longValue() 통일 — int 캐스트 금지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SearchIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    // [Crown-jewel #7] BigmPostgresImage 사용 확인
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_search_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@search-it.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
        registry.add("oauth.apple.private-key", () -> "");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.execute("DELETE FROM articles");
        jdbcTemplate.execute("DELETE FROM follow_keywords");
        jdbcTemplate.execute("DELETE FROM user_interests");
        jdbcTemplate.execute("DELETE FROM reading_preferences");
        jdbcTemplate.execute("DELETE FROM consent_records");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM accounts WHERE email != 'admin@search-it.local'");

        userToken = registerAndLoginUser("search-test@example.com");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'search-test@example.com'",
                UUID.class);
    }

    // ── [SI1] bigram match: "경제" → "경제성장" included, 무관 기사 excluded ────

    @Test
    @DisplayName("[SI1] bigram '경제' → 경제성장 포함 / 날씨 소식 미포함")
    void search_bigramMatch_includesAndExcludes() {
        Long econId = insertArticle("경제성장의 비결", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        Long unrelId = insertArticle("날씨 소식: 맑음", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=경제")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");

        List<Long> ids = articles.stream()
                .map(a -> ((Number) a.get("id")).longValue())
                .toList();

        assertThat(ids).contains(econId);
        assertThat(ids).doesNotContain(unrelId);
    }

    // ── [SI2] GREATEST(bigm_similarity): summary-strong > title-only ──────────

    @Test
    @DisplayName("[SI2] GREATEST: 요약 강한 기사가 제목만 일치 기사보다 상위")
    void search_greatest_summaryStrongRanksAboveTitleOnly() {
        // Article titleOnly: title = "경제면", no summary
        Long titleOnlyId = insertArticle("경제면", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        // Article summaryStrong: title = "기술 동향", summary = "경제 경제 경제 경제 경제"
        Long summaryStrongId = insertArticle("기술 동향", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        jdbcTemplate.update(
                "INSERT INTO summaries"
                        + " (article_id, depth, status, content, retry_count, ai_generated, created_at, updated_at)"
                        + " VALUES (?, 'BALANCED', 'COMPLETED', ?, 0, true, NOW(), NOW())",
                summaryStrongId, "경제 경제 경제 경제 경제");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=경제")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");
        assertThat(articles).hasSize(2);

        Long firstId = ((Number) articles.get(0).get("id")).longValue();
        Long secondId = ((Number) articles.get(1).get("id")).longValue();

        assertThat(firstId).isEqualTo(summaryStrongId);
        assertThat(secondId).isEqualTo(titleOnlyId);
    }

    // ── [SI3] LIKE binding: 결과 > 0 ('% || :q || %' 방식, 리터럴 아님) ─────────

    @Test
    @DisplayName("[SI3] LIKE '%'||:q||'%' 바인딩 → 결과 > 0 보장")
    void search_likeBinding_returnsResults() {
        insertArticle("경제성장 뉴스", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=경제")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<?> articles = (List<?>) data.get("articles");
        assertThat(articles).isNotEmpty();
    }

    // ── [SI4] 90일 초과 기사 제외 ────────────────────────────────────────────────

    @Test
    @DisplayName("[SI4] 90일 초과 기사 → 검색 결과 제외 단언")
    void search_oldArticle_excludedBy90DayFilter() {
        Long oldId = insertArticle("경제 오래된 기사", OffsetDateTime.now(ZoneOffset.UTC).minusDays(91));
        Long recentId = insertArticle("경제 최신 기사", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=경제")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");

        List<Long> ids = articles.stream()
                .map(a -> ((Number) a.get("id")).longValue())
                .toList();

        assertThat(ids).contains(recentId);
        assertThat(ids).doesNotContain(oldId);
    }

    // ── [SI5] 입력 검증 422: 빈 쿼리 / 1자 / 101자 ──────────────────────────────

    @Test
    @DisplayName("[SI5-a] 빈 쿼리 → 422")
    void search_emptyQuery_returns422() {
        assertThatThrownBy(() -> restClient.get()
                .uri("/api/v1/articles/search?q=")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(422));
    }

    @Test
    @DisplayName("[SI5-b] 1자 쿼리 → 422 (min=2)")
    void search_singleChar_returns422() {
        assertThatThrownBy(() -> restClient.get()
                .uri("/api/v1/articles/search?q=경")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(422));
    }

    @Test
    @DisplayName("[SI5-c] 101자 쿼리 → 422")
    void search_over100Chars_returns422() {
        String longQuery = "경".repeat(101);
        assertThatThrownBy(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/articles/search")
                        .queryParam("q", longQuery)
                        .build())
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(422));
    }

    // ── [SI6] FR-013: 관심사 유무 무관 동일 relevance 정렬 ───────────────────────

    @Test
    @DisplayName("[SI6] FR-013: 관심사 없는 유저와 있는 유저, 동일 relevance 순서")
    void search_fr013_sameOrderRegardlessOfInterests() {
        // Two articles with different relevance
        Long highRelId = insertArticle("경제 경제", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        Long lowRelId  = insertArticle("경제면",   OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        // User without interests (userToken from setUp)
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> noInterestArticles = (List<Map<?, ?>>) ((Map<String, Object>)
                restClient.get()
                        .uri("/api/v1/articles/search?q=경제")
                        .header("Authorization", "Bearer " + userToken)
                        .retrieve()
                        .body(Map.class)
                        .get("data"))
                .get("articles");

        // User WITH interests
        String interestUserToken = registerAndLoginUser("search-interest@example.com");
        UUID interestUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'search-interest@example.com'",
                UUID.class);
        jdbcTemplate.update(
                "INSERT INTO user_interests (id, account_id, category) VALUES (gen_random_uuid(), ?, ?)",
                interestUserId, "ECONOMY_FINANCE");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> interestArticles = (List<Map<?, ?>>) ((Map<String, Object>)
                restClient.get()
                        .uri("/api/v1/articles/search?q=경제")
                        .header("Authorization", "Bearer " + interestUserToken)
                        .retrieve()
                        .body(Map.class)
                        .get("data"))
                .get("articles");

        // Both users must see same article order (relevance-only, no personalization)
        List<Long> noInterestOrder = noInterestArticles.stream()
                .map(a -> ((Number) a.get("id")).longValue())
                .toList();
        List<Long> interestOrder = interestArticles.stream()
                .map(a -> ((Number) a.get("id")).longValue())
                .toList();

        assertThat(noInterestOrder).isEqualTo(interestOrder);
        // Both must rank high-relevance before low-relevance
        assertThat(noInterestOrder.get(0)).isEqualTo(highRelId);
    }

    // ── [SI7] 0건 → 200 빈 목록 ──────────────────────────────────────────────

    @Test
    @DisplayName("[SI7] 검색 결과 0건 → 200 빈 목록")
    void search_noResults_returns200EmptyList() {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=완전히무관한쿼리zxqwerty")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<?> articles = (List<?>) data.get("articles");
        assertThat(articles).isEmpty();
        assertThat(data.get("hasNext")).isEqualTo(false);
    }

    // ── [SI8] 커서 2-페이지: 중복 없음 ──────────────────────────────────────────

    @Test
    @DisplayName("[SI8] 커서 2-페이지: 두 페이지 합 = 전체, 중복 없음")
    void search_cursorPagination_noDuplicates() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        Long id1 = insertArticle("경제성장률", base.minusHours(1));
        Long id2 = insertArticle("경제정책", base.minusHours(2));
        Long id3 = insertArticle("경제지표", base.minusHours(3));

        // Page 1 (size=2)
        @SuppressWarnings("unchecked")
        Map<String, Object> p1Data = (Map<String, Object>) restClient.get()
                .uri("/api/v1/articles/search?q=경제&size=2")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        assertThat(p1Data.get("hasNext")).isEqualTo(true);
        String cursor = (String) p1Data.get("nextCursor");
        assertThat(cursor).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p1 = (List<Map<?, ?>>) p1Data.get("articles");
        assertThat(p1).hasSize(2);

        // Page 2
        @SuppressWarnings("unchecked")
        Map<String, Object> p2Data = (Map<String, Object>) restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/articles/search")
                        .queryParam("q", "경제")
                        .queryParam("size", "2")
                        .queryParam("cursor", cursor)
                        .build())
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class)
                .get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p2 = (List<Map<?, ?>>) p2Data.get("articles");
        assertThat(p2).hasSize(1);

        Set<Long> p1Ids = p1.stream().map(a -> ((Number) a.get("id")).longValue()).collect(Collectors.toSet());
        Set<Long> p2Ids = p2.stream().map(a -> ((Number) a.get("id")).longValue()).collect(Collectors.toSet());

        // No duplicates between pages
        assertThat(p2Ids).doesNotContainAnyElementsOf(p1Ids);
        // Together cover all 3 articles (skip 없음)
        assertThat(p1Ids.size() + p2Ids.size()).isEqualTo(3);

        // (c) relevance 순서: 경제정책·경제지표 동률(bigm 1/3) → published_at DESC 타이브레이크
        //     경제성장률 score=1/4 < 1/3 → page2로 밀림
        List<Long> p1OrderedIds = p1.stream()
                .map(a -> ((Number) a.get("id")).longValue())
                .toList();
        assertThat(p1OrderedIds.get(0)).isEqualTo(id2); // 경제정책 (minusHours(2), newer)
        assertThat(p1OrderedIds.get(1)).isEqualTo(id3); // 경제지표 (minusHours(3))
        assertThat(p2Ids).containsExactly(id1);         // 경제성장률 (score 낮아 page2)
    }

    // ── [SI9] 미인증 → 401 ───────────────────────────────────────────────────

    @Test
    @DisplayName("[SI9] 미인증 요청 → 401")
    void search_unauthenticated_returns401() {
        assertThatThrownBy(() -> restClient.get()
                .uri("/api/v1/articles/search?q=경제")
                .retrieve()
                .toBodilessEntity())
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
                .retrieve()
                .toBodilessEntity();

        jdbcTemplate.update(
                "UPDATE accounts SET email_verified = true WHERE email = ?", email);

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

    private Long insertArticle(String title, OffsetDateTime publishedAt) {
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM sources LIMIT 1", Long.class);
        if (sourceId == null) {
            jdbcTemplate.update(
                    "INSERT INTO sources (name, feed_url, adapter_type) VALUES (?, ?, ?)",
                    "테스트 매체", "https://search-test.example.com/feed", "RSS");
            sourceId = jdbcTemplate.queryForObject(
                    "SELECT id FROM sources LIMIT 1", Long.class);
        }

        String url = "https://search-it.example.com/" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles"
                        + " (normalized_url, original_url, title, published_at,"
                        + "  first_collected_at, category_status, summary_status,"
                        + "  expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, title, publishedAt, publishedAt,
                publishedAt.plusDays(90));

        Long articleId = jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);

        jdbcTemplate.update(
                "INSERT INTO article_sources (article_id, source_id, collected_at) VALUES (?, ?, ?)",
                articleId, sourceId, publishedAt);

        return articleId;
    }
}
