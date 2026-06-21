package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T040: 편향 API 인가 통합 테스트 — 실제 SecurityConfig 로딩(@SpringBootTest, standalone MockMvc 아님).
 *
 * <p>FR-006/007/009: bias 조회는 JWT 필수(미인증 401). Admin backfill은 ROLE_ADMIN(USER는 403).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class BiasAuthorizationIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_bias_authz_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_PASSWORD = "Admin@authz123!";
    private static final String ADMIN_HASH = encoder.encode(ADMIN_PASSWORD);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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
        registry.add("email-service.from-address", () -> "test@test.local");
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@authz.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TermsVersionRepository termsVersionRepository;

    private RestClient restClient;
    private UUID serviceTermsId;
    private UUID privacyTermsId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
        wireMock.stubFor(post(urlPathEqualTo("/emails")).willReturn(aResponse().withStatus(200)));

        jdbcTemplate.execute("TRUNCATE TABLE consent_records, verification_codes,"
                + " refresh_tokens, accounts RESTART IDENTITY CASCADE");
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'ADMIN', 'ACTIVE', true, 'EMAIL') ON CONFLICT (email) DO NOTHING",
                "admin@authz.local", ADMIN_HASH);

        List<TermsVersion> active = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = active.stream().filter(t -> t.getType().name().equals("SERVICE"))
                .findFirst().map(TermsVersion::getId).orElseThrow();
        privacyTermsId = active.stream().filter(t -> t.getType().name().equals("PRIVACY"))
                .findFirst().map(TermsVersion::getId).orElseThrow();
    }

    private String userToken(String email) {
        restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", email, "password", "Password1",
                        "consents", List.of(
                                Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                                Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)),
                        "ageConfirmed", true))
                .retrieve().body(Map.class);
        return accessToken(email, "Password1");
    }

    private String accessToken(String email, String password) {
        Map<?, ?> resp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().body(Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        return (String) ((Map<?, ?>) data.get("tokens")).get("accessToken");
    }

    private HttpStatus statusOf(Runnable call) {
        try {
            call.run();
            return null; // 예외 없으면 2xx
        } catch (HttpClientErrorException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    // ── 미인증(JWT 없음) → 401 ──────────────────────────────────────

    @Test
    @DisplayName("JWT 없이 칩/출처집계/스펙트럼 조회 → 401")
    void noJwt_biasReads_401() {
        assertThat(statusOf(() -> restClient.get().uri("/api/v1/articles/1/bias")
                .retrieve().toBodilessEntity())).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(statusOf(() -> restClient.get().uri("/api/v1/bias/outlets/1")
                .retrieve().toBodilessEntity())).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(statusOf(() -> restClient.get().uri("/api/v1/bias/spectrum")
                .retrieve().toBodilessEntity())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("JWT 없이 backfill → 401")
    void noJwt_backfill_401() {
        assertThat(statusOf(() -> restClient.post().uri("/api/v1/admin/bias/backfill")
                .retrieve().toBodilessEntity())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 일반 USER → backfill 403 ────────────────────────────────────

    @Test
    @DisplayName("일반 USER JWT로 backfill → 403")
    void userJwt_backfill_403() {
        String token = userToken("user@authz.com");

        assertThat(statusOf(() -> restClient.post().uri("/api/v1/admin/bias/backfill")
                .header("Authorization", "Bearer " + token)
                .retrieve().toBodilessEntity())).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── ROLE_ADMIN → backfill 202 ───────────────────────────────────

    @Test
    @DisplayName("ROLE_ADMIN JWT로 backfill → 202 + created")
    void adminJwt_backfill_202() {
        String token = accessToken("admin@authz.local", ADMIN_PASSWORD);

        var resp = restClient.post().uri("/api/v1/admin/bias/backfill")
                .header("Authorization", "Bearer " + token)
                .retrieve().toBodilessEntity();

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
    }
}
