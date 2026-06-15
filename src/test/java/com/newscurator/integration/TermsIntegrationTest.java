package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
import java.nio.file.Path;
import java.time.LocalDate;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class TermsIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

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
                    .withDatabaseName("newscurator_terms_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_HASH = encoder.encode("Admin@test123!");
    private static final String ADMIN_EMAIL = "admin@terms.local";
    private static final String ADMIN_PASSWORD = "Admin@test123!";

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
        registry.add("spring.flyway.placeholders.admin-email", () -> ADMIN_EMAIL);
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
        registry.add("oauth.apple.private-key", () -> "");
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

        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)));

        jdbcTemplate.execute(
                "TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        // Re-seed admin account (truncate removes it)
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) " +
                "VALUES (?, ?, 'ADMIN', 'ACTIVE', TRUE, 'EMAIL') ON CONFLICT (email) DO NOTHING",
                ADMIN_EMAIL, ADMIN_HASH);

        // Remove test-created terms versions and restore seed versions to active
        jdbcTemplate.execute("DELETE FROM terms_versions WHERE version != '1.0'");
        jdbcTemplate.execute("UPDATE terms_versions SET is_active = TRUE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    // ─── helpers ───

    private String adminToken() {
        // Admin exists from Flyway seed
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))
                .retrieve().body(Map.class);
        return (String) ((Map<?, ?>) ((Map<?, ?>) loginResp.get("data")).get("tokens")).get("accessToken");
    }

    private String signupAndGetToken(String email) {
        restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", email,
                        "password", "Password1!",
                        "consents", List.of(
                                Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                                Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)),
                        "ageConfirmed", true))
                .retrieve().body(Map.class);
        jdbcTemplate.update("UPDATE accounts SET email_verified = TRUE WHERE LOWER(email) = ?",
                email.toLowerCase());
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Password1!"))
                .retrieve().body(Map.class);
        return (String) ((Map<?, ?>) ((Map<?, ?>) loginResp.get("data")).get("tokens")).get("accessToken");
    }

    // ─── tests ───

    @Test
    @DisplayName("GET /api/v1/terms → 인증 없이 200 (public)")
    void getTerms_noAuth_returns200() {
        int status = restClient.get().uri("/api/v1/terms")
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /api/v1/admin/terms: USER → 403")
    void createTerms_userRole_returns403() {
        String userToken = signupAndGetToken("user_terms@example.com");

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "SERVICE", "version", "v99",
                        "effectiveDate", LocalDate.now().plusDays(1).toString(), "required", true))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("POST /api/v1/admin/terms: ADMIN → 201 + 기존 동일 type is_active=false")
    void createTerms_adminRole_returns201AndDeactivatesOldVersion() {
        String adminToken = adminToken();

        int status = restClient.post().uri("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "SERVICE", "version", "v2.0",
                        "effectiveDate", LocalDate.now().plusDays(1).toString(), "required", true))
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(201);

        // Old service terms version should be deactivated
        Boolean oldActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM terms_versions WHERE id = ?::uuid", Boolean.class, serviceTermsId.toString());
        assertThat(oldActive).isFalse();

        // New version is active
        Integer newActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM terms_versions WHERE type = 'SERVICE' AND version = 'v2.0' AND is_active = TRUE",
                Integer.class);
        assertThat(newActive).isEqualTo(1);
    }

    @Test
    @DisplayName("[US8 S1] 새 필수 약관 → 기존 유저 requiresReConsent=true → 재동의 → requiresReConsent=false")
    void newRequiredTerms_existingUserRequiresReConsent_thenSubmitConsent_clearsFlag() {
        // 1. User signs up (consents to current terms)
        String userToken = signupAndGetToken("reconsent@example.com");

        // Verify current state: requiresReConsent=false
        Map<?, ?> meBefore = (Map<?, ?>) restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().body(Map.class).get("data");
        assertThat(meBefore.get("requiresReConsent")).isEqualTo(false);

        // 2. Admin publishes new required SERVICE terms version
        String adminToken = adminToken();
        Map<?, ?> newVersionResp = (Map<?, ?>) restClient.post().uri("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "SERVICE", "version", "v3.0",
                        "effectiveDate", LocalDate.now().toString(), "required", true))
                .retrieve().body(Map.class).get("data");

        String newVersionId = newVersionResp.get("id").toString();

        // 3. Existing user checks /me → requiresReConsent=true
        Map<?, ?> meAfterNewTerms = (Map<?, ?>) restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().body(Map.class).get("data");
        assertThat(meAfterNewTerms.get("requiresReConsent")).isEqualTo(true);

        // 4. User submits consent to new version
        restClient.post().uri("/api/v1/me/consents")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("termsVersionId", newVersionId, "agreed", true)))
                .retrieve().toBodilessEntity();

        // 5. requiresReConsent=false after re-consent
        Map<?, ?> meAfterConsent = (Map<?, ?>) restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().body(Map.class).get("data");
        assertThat(meAfterConsent.get("requiresReConsent")).isEqualTo(false);
    }

    @Test
    @DisplayName("POST /me/consents 멱등 — 같은 버전 재제출 무시")
    void submitConsents_idempotent_duplicateIgnored() {
        String token = signupAndGetToken("idempotent@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "idempotent@example.com");

        int countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_records WHERE account_id = ?::uuid AND terms_version_id = ?::uuid",
                Integer.class, accountId, serviceTermsId.toString());

        // Re-submit the same consent
        restClient.post().uri("/api/v1/me/consents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true)))
                .retrieve().toBodilessEntity();

        int countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_records WHERE account_id = ?::uuid AND terms_version_id = ?::uuid",
                Integer.class, accountId, serviceTermsId.toString());

        // Must not have created a duplicate
        assertThat(countAfter).isEqualTo(countBefore);
    }
}
