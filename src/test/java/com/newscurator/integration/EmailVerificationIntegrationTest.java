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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class EmailVerificationIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_email_it")
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

        jdbcTemplate.execute("TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    /** signup → pendingToken 반환 (이메일 인증 전용 15분 토큰) */
    private String signupAndGetPendingToken(String email) {
        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)));

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
        return (String) ((Map<?, ?>) response.get("data")).get("pendingToken");
    }

    @Test
    @DisplayName("가입 직후 pendingToken으로 /me 접근 → 403 (이메일 미인증)")
    void unverifiedAccount_meAccess_returns403() {
        String pendingToken = signupAndGetPendingToken("unverified@example.com");

        assertThatThrownBy(() ->
                restClient.get().uri("/api/v1/me")
                        .header("Authorization", "Bearer " + pendingToken)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("코드 검증 성공 → verify가 emailVerified=true JWT 반환 → /me 접근 허용")
    void verifyCode_success_returnsTokensAndAllowsMeAccess() {
        String pendingToken = signupAndGetPendingToken("verify@example.com");

        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?",
                String.class, "verify@example.com");
        String knownCode = "123456";
        String codeHash = jwtTokenProvider.sha256Hex(knownCode);
        jdbcTemplate.update(
                "INSERT INTO verification_codes (account_id, purpose, code_hash, expires_at, window_start) " +
                "VALUES (?::uuid, 'EMAIL_VERIFY', ?, NOW() + INTERVAL '15 minutes', NOW())",
                accountId, codeHash);

        // verify → 200 + tokens (emailVerified=true JWT 포함)
        Map<?, ?> verifyResp = restClient.post().uri("/api/v1/auth/email-verification/verify")
                .header("Authorization", "Bearer " + pendingToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", knownCode))
                .retrieve()
                .body(Map.class);

        Map<?, ?> verifyData = (Map<?, ?>) verifyResp.get("data");
        String newAccessToken = (String) ((Map<?, ?>) verifyData.get("tokens")).get("accessToken");
        assertThat(newAccessToken).isNotBlank();
        assertThat(((Map<?, ?>) verifyData.get("account")).get("emailVerified")).isEqualTo(true);

        // verify가 반환한 토큰으로 /me 즉시 접근 가능 (재로그인 불필요)
        Map<?, ?> meFresh = restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + newAccessToken)
                .retrieve()
                .body(Map.class);
        assertThat(((Map<?, ?>) meFresh.get("data")).get("emailVerified")).isEqualTo(true);
    }

    @Test
    @DisplayName("만료된 코드 검증 → 410")
    void verifyCode_expired_returns410() {
        String pendingToken = signupAndGetPendingToken("expired@example.com");

        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?",
                String.class, "expired@example.com");
        String knownCode = "999999";
        String codeHash = jwtTokenProvider.sha256Hex(knownCode);
        jdbcTemplate.update(
                "INSERT INTO verification_codes (account_id, purpose, code_hash, expires_at, window_start) " +
                "VALUES (?::uuid, 'EMAIL_VERIFY', ?, NOW() - INTERVAL '1 minute', NOW() - INTERVAL '16 minutes')",
                accountId, codeHash);

        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/email-verification/verify")
                        .header("Authorization", "Bearer " + pendingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("code", knownCode))
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.GONE));
    }

    @Test
    @DisplayName("이메일 발송 실패 503 → 코드 미생성·한도 미차감")
    void requestCode_emailFailure_returns503AndQuotaNotCharged() {
        String pendingToken = signupAndGetPendingToken("emailfail@example.com");
        String accessToken = pendingToken;

        // Count current verification codes and hourly_count before
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?",
                String.class, "emailfail@example.com");
        int countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_codes WHERE account_id = ?::uuid AND is_used = false",
                Integer.class, accountId);
        int maxHourlyBefore = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(hourly_count), 0) FROM verification_codes WHERE account_id = ?::uuid",
                Integer.class, accountId);

        // Stub email to fail
        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/email-verification/request")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("email", "emailfail@example.com"))
                        .retrieve()
                        .body(Void.class))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(e -> assertThat(((HttpServerErrorException) e).getStatusCode().value())
                        .isEqualTo(503));

        // Code count should not have increased
        int countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_codes WHERE account_id = ?::uuid AND is_used = false",
                Integer.class, accountId);
        assertThat(countAfter).isEqualTo(countBefore);

        // hourly_count (quota counter) must remain unchanged
        int maxHourlyAfter = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(hourly_count), 0) FROM verification_codes WHERE account_id = ?::uuid",
                Integer.class, accountId);
        assertThat(maxHourlyAfter).isEqualTo(maxHourlyBefore);
    }
}
