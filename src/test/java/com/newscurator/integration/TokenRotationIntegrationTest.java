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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class TokenRotationIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_token_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@test.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TermsVersionRepository termsVersionRepository;

    private RestClient restClient;
    private UUID serviceTermsId;
    private UUID privacyTermsId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)));

        jdbcTemplate.execute("TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    private Map<?, ?> signup(String email) {
        Map<String, Object> body = Map.of(
                "email", email,
                "password", "Password1",
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );
        Map<?, ?> response = restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return (Map<?, ?>) response.get("data");
    }

    private String extractRefreshToken(Map<?, ?> data) {
        return (String) ((Map<?, ?>) data.get("tokens")).get("refreshToken");
    }

    private Map<?, ?> refresh(String rawRefreshToken) {
        Map<?, ?> response = restClient.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", rawRefreshToken))
                .retrieve()
                .body(Map.class);
        return (Map<?, ?>) response.get("data");
    }

    @Test
    @DisplayName("정상 토큰 갱신: 새 토큰 발급, 이전 토큰과 다름, 새 토큰으로 재갱신 가능")
    void refresh_normalRotation_issuesNewToken() {
        Map<?, ?> signupResp = signup("rotation1@example.com");
        String original = extractRefreshToken(signupResp);

        Map<?, ?> refreshResp = refresh(original);
        String newToken = (String) refreshResp.get("refreshToken");

        assertThat(newToken).isNotBlank().isNotEqualTo(original);
        assertThat(refreshResp.get("accessToken")).asString().isNotBlank();

        // New token can be used to rotate again (proving it is valid)
        Map<?, ?> secondRefresh = refresh(newToken);
        assertThat(secondRefresh.get("refreshToken")).asString().isNotBlank();
    }

    @Test
    @DisplayName("grace 30s 내 재사용: 멱등 응답 (새 토큰 반환, 에러 없음)")
    void refresh_withinGracePeriod_idempotent() {
        // The service handles grace: within 30s of consuming, returns a new token in the same family
        // We can't fake time in integration, but we can verify that immediate reuse doesn't throw
        Map<?, ?> signupResp = signup("grace@example.com");
        String original = extractRefreshToken(signupResp);

        // First rotation: consume original
        Map<?, ?> first = refresh(original);
        String firstNew = (String) first.get("refreshToken");

        // Immediate re-attempt with original (within grace window since it was just consumed)
        Map<?, ?> gracedResp = refresh(original);
        String gracedNew = (String) gracedResp.get("refreshToken");

        assertThat(gracedNew).isNotBlank();
        // Both returned tokens are usable (not errors)
        assertThat(gracedResp.get("accessToken")).asString().isNotBlank();
    }

    @Test
    @DisplayName("grace 초과 재사용: family blast (401 TokenReused)")
    void refresh_gracePeriodExceeded_familyBlast() {
        Map<?, ?> signupResp = signup("blast@example.com");
        String original = extractRefreshToken(signupResp);

        // Consume the token normally
        Map<?, ?> firstResp = refresh(original);
        String firstNewToken = (String) firstResp.get("refreshToken");

        // Manually update consumed_at to 60 seconds ago to simulate grace expiry
        jdbcTemplate.update(
                "UPDATE refresh_tokens SET consumed_at = NOW() - INTERVAL '60 seconds' " +
                "WHERE is_revoked = false OR consumed_at IS NOT NULL",
                new Object[0]);

        // Reusing the original token after grace period should blast the family
        assertThatThrownBy(() -> refresh(original))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isIn(401));

        // The new token from first rotation should also be revoked (family blast)
        assertThatThrownBy(() -> refresh(firstNewToken))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    @DisplayName("5분 내 2회 family blast → account-wide 무효화 + 401")
    void refresh_twoFamilyBlastsInFiveMin_accountWideRevocation() {
        Map<?, ?> signupResp = signup("escalation@example.com");
        String originalToken = extractRefreshToken(signupResp);

        // Rotate once to get a new family member
        Map<?, ?> rotated = refresh(originalToken);
        String secondToken = (String) rotated.get("refreshToken");

        // Now simulate two separate consumed tokens to trigger 2 blast detections
        // For this integration test, we directly manipulate DB to simulate past blast
        jdbcTemplate.update(
                "UPDATE refresh_tokens SET consumed_at = NOW() - INTERVAL '60 seconds'",
                new Object[0]);

        // Also insert a mock already-blasted family to simulate prior blast within 5 min
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?",
                String.class, "escalation@example.com");

        // Insert a "blasted" token for a different family within the last 5 minutes.
        // Must set blasted_at (not just consumed_at + is_revoked) — the query uses blastedAt IS NOT NULL.
        UUID otherFamily = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (account_id, family_id, token_hash, issued_at, expires_at,"
                        + " is_revoked, consumed_at, blasted_at) "
                        + "VALUES (?::uuid, ?::uuid, 'blasted_hash_1',"
                        + " NOW() - INTERVAL '2 minutes', NOW() + INTERVAL '30 days',"
                        + " true, NOW() - INTERVAL '3 minutes', NOW() - INTERVAL '3 minutes')",
                accountId, otherFamily.toString());

        // Reuse original after grace — should escalate to account-wide
        assertThatThrownBy(() -> refresh(originalToken))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(401));

        // secondToken from same account should also fail now (account blast)
        assertThatThrownBy(() -> refresh(secondToken))
                .isInstanceOf(HttpClientErrorException.class);
    }
}
