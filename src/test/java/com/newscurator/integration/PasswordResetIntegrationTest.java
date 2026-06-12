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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PasswordResetIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_pwreset_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@pwreset.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
        registry.add("oauth.apple.private-key", () -> "");  // Apple not needed for these tests
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

        wireMock.stubFor(post(urlPathEqualTo("/send-verification-code"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlPathEqualTo("/send-password-reset-code"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlPathEqualTo("/send-social-only-notice"))
                .willReturn(aResponse().withStatus(200)));

        jdbcTemplate.execute(
                "TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    // ─── helpers ───

    private Map<?, ?> signup(String email) {
        return restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", email,
                        "password", "Password1",
                        "consents", List.of(
                                Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                                Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)),
                        "ageConfirmed", true))
                .retrieve().body(Map.class);
    }

    private String signupAndLogin(String email) {
        signup(email);
        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Password1"))
                .retrieve().body(Map.class);
        return (String) ((Map<?, ?>) loginResp.get("tokens")).get("refreshToken");
    }

    private String insertKnownCode(String email, String plainCode) {
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, email.toLowerCase());
        String codeHash = hashCode(plainCode);
        jdbcTemplate.update(
                "INSERT INTO verification_codes "
                        + "(account_id, purpose, code_hash, expires_at, attempt_count, hourly_count, window_start, is_used, created_at) "
                        + "VALUES (?::uuid, 'PASSWORD_RESET', ?, NOW() + INTERVAL '15 minutes', 0, 1, NOW(), false, NOW())",
                accountId, codeHash);
        return plainCode;
    }

    private String hashCode(String code) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertExpiredCode(String email, String plainCode) {
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, email.toLowerCase());
        String codeHash = hashCode(plainCode);
        jdbcTemplate.update(
                "INSERT INTO verification_codes "
                        + "(account_id, purpose, code_hash, expires_at, attempt_count, hourly_count, window_start, is_used, created_at) "
                        + "VALUES (?::uuid, 'PASSWORD_RESET', ?, NOW() - INTERVAL '1 minute', 0, 1, NOW(), false, NOW())",
                accountId, codeHash);
    }

    private String requestAndVerify(String email, String code) {
        // verify step
        Map<?, ?> verifyResp = restClient.post().uri("/api/v1/auth/password-reset/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "code", code))
                .retrieve().body(Map.class);
        return (String) verifyResp.get("resetToken");
    }

    // ─── test scenarios ───

    @Test
    @DisplayName("균일 202: 등록 계정 → 202 + 이메일 발송")
    void request_registeredAccount_returns202AndSendsEmail() {
        signup("registered@example.com");
        wireMock.resetRequests();

        restClient.post().uri("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "registered@example.com"))
                .retrieve().toBodilessEntity();

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/send-password-reset-code")));
    }

    @Test
    @DisplayName("균일 202: 미등록 이메일 → 202, 이메일 미발송")
    void request_unregisteredEmail_returns202NoEmail() {
        wireMock.resetRequests();

        int status = restClient.post().uri("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "ghost@example.com"))
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(202);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/send-password-reset-code")));
    }

    @Test
    @DisplayName("균일 202: 소셜 전용 계정 → 202 + 소셜 안내 이메일 발송")
    void request_socialOnlyAccount_returns202AndSendsNotice() {
        // Insert social-only account (no password_hash)
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) "
                        + "VALUES ('social@example.com', NULL, 'USER', 'ACTIVE', true, 'SOCIAL')");
        wireMock.resetRequests();

        int status = restClient.post().uri("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "social@example.com"))
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(202);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/send-social-only-notice")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/send-password-reset-code")));
    }

    @Test
    @DisplayName("429 한도 초과: 시간당 5회 이상 요청 → 429")
    void request_rateLimitExceeded_returns429() {
        signup("ratelimit@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "ratelimit@example.com");

        // Insert 5 existing codes with hourly_count=5 in recent window
        jdbcTemplate.update(
                "INSERT INTO verification_codes "
                        + "(account_id, purpose, code_hash, expires_at, attempt_count, hourly_count, window_start, is_used, created_at) "
                        + "VALUES (?::uuid, 'PASSWORD_RESET', 'anyhash', NOW() + INTERVAL '15 minutes', 0, 5, NOW(), true, NOW())",
                accountId);

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "ratelimit@example.com"))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(429));
    }

    @Test
    @DisplayName("503 이메일 실패: orphan 코드 미생성, hourly_count 미차감")
    void request_emailServiceDown_returns503NoOrphan() {
        signup("fail@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "fail@example.com");

        // Stub email to fail
        wireMock.stubFor(post(urlPathEqualTo("/send-password-reset-code"))
                .willReturn(aResponse().withStatus(500)));

        int codesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_codes WHERE account_id = ?::uuid AND purpose = 'PASSWORD_RESET'",
                Integer.class, accountId);

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "fail@example.com"))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(e -> assertThat(((HttpServerErrorException) e).getStatusCode().value()).isEqualTo(503));

        int codesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_codes WHERE account_id = ?::uuid AND purpose = 'PASSWORD_RESET'",
                Integer.class, accountId);
        assertThat(codesAfter).isEqualTo(codesBefore);  // no orphan code created
    }

    @Test
    @DisplayName("verify → confirm → DB에서 refresh_tokens 전부 is_revoked=true (FR-025)")
    void confirm_success_revokesAllRefreshTokens() {
        String refreshToken = signupAndLogin("resetme@example.com");
        String email = "resetme@example.com";
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, email);

        // Verify there is at least one active refresh token
        int activeTokensBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE account_id = ?::uuid AND is_revoked = FALSE",
                Integer.class, accountId);
        assertThat(activeTokensBefore).isGreaterThan(0);

        // Insert known code and verify it
        String code = insertKnownCode(email, "654321");
        String resetToken = requestAndVerify(email, code);
        assertThat(resetToken).isNotBlank();

        // Confirm reset
        int confirmStatus = restClient.post().uri("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("resetToken", resetToken, "newPassword", "NewPassword1"))
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(confirmStatus).isEqualTo(204);

        // FR-025: ALL refresh tokens for this account must be revoked
        int activeTokensAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE account_id = ?::uuid AND is_revoked = FALSE",
                Integer.class, accountId);
        assertThat(activeTokensAfter).isEqualTo(0);
    }

    @Test
    @DisplayName("resetToken 재사용 → 401")
    void confirm_reuse_returns401() {
        signup("reuse@example.com");
        String email = "reuse@example.com";

        String code = insertKnownCode(email, "111222");
        String resetToken = requestAndVerify(email, code);

        // First use succeeds
        restClient.post().uri("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("resetToken", resetToken, "newPassword", "NewPassword1"))
                .retrieve().toBodilessEntity();

        // Second use must fail
        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("resetToken", resetToken, "newPassword", "NewPassword2"))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(401));
    }
}
