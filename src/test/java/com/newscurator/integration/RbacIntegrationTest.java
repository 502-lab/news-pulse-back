package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
import com.newscurator.security.JwtTokenProvider;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RbacIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_rbac_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_PASSWORD = "Admin@test123!";
    private static final String ADMIN_HASH = encoder.encode(ADMIN_PASSWORD);

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

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private RestClient restClient;
    private UUID serviceTermsId;
    private UUID privacyTermsId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        wireMock.stubFor(post(urlPathEqualTo("/send-verification-code"))
                .willReturn(aResponse().withStatus(200)));

        jdbcTemplate.execute("TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        // Re-seed admin account (was cleared by TRUNCATE)
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) " +
                "VALUES (?, ?, 'ADMIN', 'ACTIVE', true, 'EMAIL') ON CONFLICT (email) DO NOTHING",
                "admin@test.local", ADMIN_HASH);

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    private Map<?, ?> signupAndLogin(String email, String password) {
        Map<String, Object> signupBody = Map.of(
                "email", email,
                "password", password,
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );
        return restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(signupBody)
                .retrieve()
                .body(Map.class);
    }

    private String getAccessToken(Map<?, ?> resp) {
        return (String) ((Map<?, ?>) resp.get("tokens")).get("accessToken");
    }

    @Test
    @DisplayName("/admin/** 미인증 요청 → 401")
    void adminEndpoint_unauthenticated_returns401() {
        assertThatThrownBy(() ->
                restClient.get().uri("/api/v1/admin/test")
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("/admin/** USER 권한 요청 → 403")
    void adminEndpoint_userRole_returns403() {
        Map<?, ?> resp = signupAndLogin("user@rbac.com", "Password1");
        String accessToken = getAccessToken(resp);

        assertThatThrownBy(() ->
                restClient.get().uri("/api/v1/admin/test")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("/admin/** ADMIN 권한 요청 → 200")
    void adminEndpoint_adminRole_returns200() {
        // Login with the seeded admin account (created by V5 Flyway migration)
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin@test.local", "password", ADMIN_PASSWORD))
                .retrieve()
                .body(Map.class);
        String adminAccessToken = getAccessToken(loginResp);

        // ADMIN can access /api/v1/admin/pipeline/stats → 200
        var statsResp = restClient.get().uri("/api/v1/admin/pipeline/stats")
                .header("Authorization", "Bearer " + adminAccessToken)
                .retrieve()
                .toBodilessEntity();
        assertThat(statsResp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("인증된 USER /me → 200 (emailVerified=false면 403, true면 200)")
    void meEndpoint_verifiedUser_returns200() {
        // Create and verify an account
        Map<?, ?> resp = signupAndLogin("me@rbac.com", "Password1");
        String accessToken = getAccessToken(resp);

        // With emailVerified=false, /me should be 403 with code=EMAIL_NOT_VERIFIED
        assertThatThrownBy(() ->
                restClient.get().uri("/api/v1/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getResponseBodyAsString()).contains("EMAIL_NOT_VERIFIED");
                });

        // Manually mark email as verified
        jdbcTemplate.update(
                "UPDATE accounts SET email_verified = true WHERE LOWER(email) = ?",
                "me@rbac.com");

        // Re-login to get new token with emailVerified=true
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "me@rbac.com", "password", "Password1"))
                .retrieve()
                .body(Map.class);
        String verifiedToken = getAccessToken(loginResp);

        Map<?, ?> meResp = restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + verifiedToken)
                .retrieve()
                .body(Map.class);
        assertThat(meResp.get("email")).isEqualTo("me@rbac.com");
        assertThat(meResp.get("emailVerified")).isEqualTo(true);
    }

    @Test
    @DisplayName("미인증 계정(emailVerified=false) /me → 403, /auth/email-verification/request → 허용(200)")
    void emailVerified_false_meForbidden_emailVerificationAllowed() {
        Map<?, ?> resp = signupAndLogin("gating@rbac.com", "Password1");
        String accessToken = getAccessToken(resp);

        // /me must be forbidden for unverified account with code=EMAIL_NOT_VERIFIED
        assertThatThrownBy(() ->
                restClient.get().uri("/api/v1/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getResponseBodyAsString()).contains("EMAIL_NOT_VERIFIED");
                });

        // /auth/email-verification/request should be allowed (emailVerified=false is OK)
        var emailReqResp = restClient.post().uri("/api/v1/auth/email-verification/request")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "gating@rbac.com"))
                .retrieve()
                .toBodilessEntity();
        assertThat(emailReqResp.getStatusCode().value()).isEqualTo(200);
    }
}
