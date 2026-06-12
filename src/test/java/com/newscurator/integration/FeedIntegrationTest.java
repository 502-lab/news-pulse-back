package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Path;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FeedIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    // pg_bigm 커스텀 이미지 — V9 unconditional CREATE EXTENSION 대응
    // 정적 블록에서 이미지 먼저 build하고 이름을 PostgreSQLContainer 에 전달
    private static final DockerImageName BIGM_IMAGE_NAME;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            String resolved = new ImageFromDockerfile("postgres-bigm-test", false)
                    .withDockerfile(Path.of("src/test/resources/postgres-bigm/Dockerfile"))
                    .get();
            BIGM_IMAGE_NAME =
                    DockerImageName.parse(resolved).asCompatibleSubstituteFor("postgres");
        } else {
            BIGM_IMAGE_NAME = DockerImageName.parse("postgres:16-alpine");
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BIGM_IMAGE_NAME)
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

        jdbcTemplate.execute("DELETE FROM saved_articles");
        jdbcTemplate.execute("DELETE FROM summaries");
        jdbcTemplate.execute("DELETE FROM article_sources");
        jdbcTemplate.execute("DELETE FROM articles");
        jdbcTemplate.execute("DELETE FROM follow_keywords");
        jdbcTemplate.execute("DELETE FROM user_interests");
        jdbcTemplate.execute("DELETE FROM reading_preferences");
        jdbcTemplate.execute("DELETE FROM consent_records");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute(
                "DELETE FROM accounts WHERE email != 'admin@feed-it.local'");

        userToken = registerAndLoginUser("feed-test@example.com");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'feed-test@example.com'",
                UUID.class);
    }

    // ── IT1: category(+50) > recency(max +20) 역전 단언 ──────────────────────
    // 비관심 기사가 *더 최신*임에도 관심 카테고리 기사가 먼저 나와야 함

    @Test
    @DisplayName("[IT1] category +50이 더 최신인 비관심 기사의 recency 우위(최대 +20)를 역전")
    void getFeed_categoryBeatsNewerNonInterestArticle() {
        jdbcTemplate.update(
                "INSERT INTO user_interests (id, account_id, category)"
                        + " VALUES (gen_random_uuid(), ?, ?)",
                userId, "ECONOMY_FINANCE");

        // 오래된 관심 기사: category +50, recency ≈ +3.3 (25h) → 합 ≈ 53
        Long econId = insertArticle(
                "경제 오래된 뉴스", "ECONOMY_FINANCE",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(25));
        // 최신 비관심 기사: category 0, recency ≈ +20 (5min) → 합 ≈ 20
        Long polId = insertArticle(
                "정치 최신 뉴스", "POLITICS",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.get()
                .uri("/api/v1/feed")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data.get("personalized")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");
        assertThat(articles).hasSize(2);
        // 오래된 관심 기사가 최신 비관심 기사보다 앞에 위치
        assertThat(articles.get(0).get("id")).isEqualTo(econId.intValue());
        assertThat(articles.get(1).get("id")).isEqualTo(polId.intValue());
    }

    // ── IT2: 관심사 없음 → personalized=false, publishedAt DESC ──────────────

    @Test
    @DisplayName("[IT2] 관심사·키워드 없음 → personalized=false, 최신순 반환")
    void getFeed_noInterests_fallbackLatest() {
        OffsetDateTime older = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5);
        OffsetDateTime newer = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        Long oldId = insertArticle("오래된 뉴스", "POLITICS", older);
        Long newId = insertArticle("최신 뉴스", "IT", newer);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.get()
                .uri("/api/v1/feed")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data.get("personalized")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> articles = (List<Map<?, ?>>) data.get("articles");
        assertThat(articles.get(0).get("id")).isEqualTo(newId.intValue());
        assertThat(articles.get(1).get("id")).isEqualTo(oldId.intValue());
    }

    // ── IT3: 비개인화 커서 — 중복 없음 + publishedAt DESC 순서 완전 일치 ──────

    @Test
    @DisplayName("[IT3] 비개인화 커서 2-페이지: 중복 없음 + 전체 5개 publishedAt DESC 순서 일치")
    void getFeed_cursorPagination_noDuplicatesAndOrder() {
        // i=0이 가장 최신, i=4가 가장 오래됨
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        Long[] ids = new Long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = insertArticle("기사 " + i, "IT", base.minusMinutes(i * 10L));
        }

        // Page 1: size=3
        @SuppressWarnings("unchecked")
        Map<String, Object> page1Resp = restClient.get()
                .uri("/api/v1/feed?size=3")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p1Data = (Map<String, Object>) page1Resp.get("data");
        assertThat(p1Data.get("hasNext")).isEqualTo(true);
        String nextCursor = (String) p1Data.get("nextCursor");
        assertThat(nextCursor).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p1 = (List<Map<?, ?>>) p1Data.get("articles");
        assertThat(p1).hasSize(3);
        // page1 순서 단언 (top-3 최신순)
        assertThat(p1.get(0).get("id")).isEqualTo(ids[0].intValue());
        assertThat(p1.get(1).get("id")).isEqualTo(ids[1].intValue());
        assertThat(p1.get(2).get("id")).isEqualTo(ids[2].intValue());

        // Page 2
        @SuppressWarnings("unchecked")
        Map<String, Object> page2Resp = restClient.get()
                .uri("/api/v1/feed?size=3&cursor=" + nextCursor)
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p2Data = (Map<String, Object>) page2Resp.get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p2 = (List<Map<?, ?>>) p2Data.get("articles");
        assertThat(p2).hasSize(2);
        // page2 순서 단언 (4·5번째)
        assertThat(p2.get(0).get("id")).isEqualTo(ids[3].intValue());
        assertThat(p2.get(1).get("id")).isEqualTo(ids[4].intValue());

        // 중복 없음
        Set<Object> p1Ids = p1.stream().map(a -> a.get("id")).collect(Collectors.toSet());
        Set<Object> p2Ids = p2.stream().map(a -> a.get("id")).collect(Collectors.toSet());
        p2Ids.forEach(id -> assertThat(p1Ids).doesNotContain(id));
        // skip 없음 (전체 5개 커버)
        assertThat(p1Ids.size() + p2Ids.size()).isEqualTo(5);
    }

    // ── IT4: 미인증 → 401 ────────────────────────────────────────────────────

    @Test
    @DisplayName("[IT4] 인증 없이 피드 요청 → 401")
    void getFeed_noToken_returns401() {
        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/api/v1/feed")
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("401");
    }

    // ── IT5: 이메일 미인증 → 403 ─────────────────────────────────────────────

    @Test
    @DisplayName("[IT5] 이메일 미인증 계정으로 피드 요청 → 403")
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

    // ── IT6: personalized 커서 — rank_score 순서 + reference_ts 격리 ──────────
    // DESC 버그가 있었던 rank_score 정렬 경로의 전용 cursor 통합 테스트.
    // 검증 3-in-1:
    //   (a) page1 rank_score DESC 순서 정확성
    //   (b) page2 중복 없음 + skip 없음 (원래 5개 전부 커버)
    //   (c) reference_ts 격리: page1 이후 삽입된 기사는 page2에 미포함
    //       → findFeedCandidates: WHERE publishedAt <= :refTs 조건으로 보장

    @Test
    @DisplayName("[IT6] personalized 커서 2-페이지: rank_score 순서 + 중복 없음 + reference_ts 격리")
    void getFeed_personalizedCursorPagination_orderAndRefTsIsolation() {
        jdbcTemplate.update(
                "INSERT INTO user_interests (id, account_id, category)"
                        + " VALUES (gen_random_uuid(), ?, ?)",
                userId, "ECONOMY_FINANCE");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // category(50) >> recency_max(20) → ECONOMY_FINANCE 3개가 top-3
        // 시간 간격 3~4h로 벌려 timing jitter 영향 최소화
        Long econNew = insertArticle("경제 최신", "ECONOMY_FINANCE", now.minusMinutes(30));
        // score ≈ 50 + 20*(1 - 0.5/30) ≈ 69.7
        Long econMid = insertArticle("경제 중간", "ECONOMY_FINANCE", now.minusHours(4));
        // score ≈ 50 + 20*(1 - 4/30)   ≈ 67.3
        Long econOld = insertArticle("경제 오래됨", "ECONOMY_FINANCE", now.minusHours(8));
        // score ≈ 50 + 20*(1 - 8/30)   ≈ 64.7
        Long polNew = insertArticle("정치 최신", "POLITICS", now.minusHours(1));
        // score ≈ 0  + 20*(1 - 1/30)   ≈ 19.3
        Long polOld = insertArticle("정치 오래됨", "POLITICS", now.minusHours(10));
        // score ≈ 0  + 20*(1 - 10/30)  ≈ 13.3

        // ── Page 1 (size=3) ──────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        Map<String, Object> page1Resp = restClient.get()
                .uri("/api/v1/feed?size=3")
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p1Data = (Map<String, Object>) page1Resp.get("data");
        assertThat(p1Data.get("personalized")).isEqualTo(true);
        assertThat(p1Data.get("hasNext")).isEqualTo(true);
        String nextCursor = (String) p1Data.get("nextCursor");
        assertThat(nextCursor).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p1 = (List<Map<?, ?>>) p1Data.get("articles");
        assertThat(p1).hasSize(3);
        // (a) page1 rank_score DESC 순서: ECONOMY_FINANCE 3개, 최신 순
        assertThat(p1.get(0).get("id")).isEqualTo(econNew.intValue());
        assertThat(p1.get(1).get("id")).isEqualTo(econMid.intValue());
        assertThat(p1.get(2).get("id")).isEqualTo(econOld.intValue());

        // (c) reference_ts 격리: page1 응답 후 publishedAt=refTs+5s 기사 삽입
        // findFeedCandidates WHERE publishedAt <= :refTs → 이 기사는 제외되어야 함
        Long afterCursor = insertArticle(
                "커서 이후 경제 뉴스", "ECONOMY_FINANCE",
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5));

        // ── Page 2 ────────────────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        Map<String, Object> page2Resp = restClient.get()
                .uri("/api/v1/feed?size=3&cursor=" + nextCursor)
                .header("Authorization", "Bearer " + userToken)
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p2Data = (Map<String, Object>) page2Resp.get("data");

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> p2 = (List<Map<?, ?>>) p2Data.get("articles");

        // (b) page2: polNew > polOld 순서, size=2 (afterCursor 기사 미포함)
        assertThat(p2).hasSize(2);
        assertThat(p2.get(0).get("id")).isEqualTo(polNew.intValue());
        assertThat(p2.get(1).get("id")).isEqualTo(polOld.intValue());

        Set<Object> p1Ids = p1.stream().map(a -> a.get("id")).collect(Collectors.toSet());
        Set<Object> p2Ids = p2.stream().map(a -> a.get("id")).collect(Collectors.toSet());

        // (c) reference_ts 격리: afterCursor 기사가 page2에 없음
        assertThat(p2Ids).doesNotContain(afterCursor.intValue());

        // (b-2) 중복 없음
        p2Ids.forEach(id -> assertThat(p1Ids).doesNotContain(id));

        // (b-3) skip 없음: 원래 5개 기사 모두 커버 (afterCursor 제외)
        assertThat(p1Ids.size() + p2Ids.size()).isEqualTo(5);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
            sourceId = jdbcTemplate.queryForObject(
                    "SELECT id FROM sources LIMIT 1", Long.class);
        }

        jdbcTemplate.update(
                "INSERT INTO articles"
                        + " (normalized_url, original_url, title, published_at,"
                        + "  first_collected_at, category, category_status, summary_status,"
                        + "  expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                "https://example.com/" + UUID.randomUUID(),
                "https://example.com/orig",
                title, publishedAt, publishedAt, category,
                publishedAt.plusDays(90));

        Long articleId = jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE title = ? ORDER BY id DESC LIMIT 1",
                Long.class, title);

        jdbcTemplate.update(
                "INSERT INTO article_sources (article_id, source_id, collected_at)"
                        + " VALUES (?, ?, ?)",
                articleId, sourceId, publishedAt);

        return articleId;
    }
}
