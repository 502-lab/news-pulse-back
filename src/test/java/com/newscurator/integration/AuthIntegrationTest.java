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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_auth_it")
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
        registry.add("email-service.from-address", () -> "test@test.local");
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
    private List<TermsVersion> activeTerms;
    private UUID serviceTermsId;
    private UUID privacyTermsId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        // Stub Resend API to succeed by default
        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)));

        // Clean data
        jdbcTemplate.execute("TRUNCATE TABLE consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    @Test
    @DisplayName("정상 가입 → 201 + 토큰 쌍 + emailVerified=false + verificationEmailSent=true")
    void signup_success_returns201WithTokens() {
        Map<String, Object> body = Map.of(
                "email", "newuser@example.com",
                "password", "Password1",
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );

        Map<?, ?> response = restClient.post()
                .uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        assertThat(response.containsKey("tokens")).isTrue();
        assertThat(response.containsKey("account")).isTrue();
        Map<?, ?> tokens = (Map<?, ?>) response.get("tokens");
        assertThat(tokens.get("accessToken")).asString().isNotBlank();
        assertThat(tokens.get("refreshToken")).asString().isNotBlank();
        Map<?, ?> account = (Map<?, ?>) response.get("account");
        assertThat(account.get("emailVerified")).isEqualTo(false);
        assertThat(account.get("role")).isEqualTo("USER");
        // verificationEmailSent must be true when email stub returns 200
        assertThat(response.get("verificationEmailSent")).isEqualTo(true);

        // Verify email send was called via Resend API
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/emails")));
    }

    @Test
    @DisplayName("가입 중 이메일 발송 503 → 계정 생성됨(rollback X), 201 + verificationEmailSent=false, orphan 코드 없음, 재발송 가능")
    void signup_emailDeliveryFails_accountCreated_verificationEmailSentFalse() {
        // Stub Resend API to fail
        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(503)));

        Map<String, Object> body = Map.of(
                "email", "emailfail@signup.com",
                "password", "Password1",
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );

        // (a) 201 반환 + verificationEmailSent=false
        Map<?, ?> response = restClient.post()
                .uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        assertThat(response.get("verificationEmailSent")).isEqualTo(false);
        assertThat(response.containsKey("tokens")).isTrue();

        // (b) 계정 row DB에 존재
        int accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE LOWER(email) = ?",
                Integer.class, "emailfail@signup.com");
        assertThat(accountCount).isEqualTo(1);

        // (c) orphan 코드 없음·한도 미차감
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?",
                String.class, "emailfail@signup.com");
        int codeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_codes WHERE account_id = ?::uuid",
                Integer.class, accountId);
        assertThat(codeCount).isEqualTo(0);

        Integer maxHourly = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(hourly_count), 0) FROM verification_codes WHERE account_id = ?::uuid",
                Integer.class, accountId);
        assertThat(maxHourly).isEqualTo(0);

        // (d) 재발송 가능: 이메일 서비스 복구 후 request 성공
        wireMock.stubFor(post(urlPathEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)));
        String accessToken = (String) ((Map<?, ?>) response.get("tokens")).get("accessToken");
        var resendResp = restClient.post()
                .uri("/api/v1/auth/email-verification/request")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "emailfail@signup.com"))
                .retrieve()
                .toBodilessEntity();
        assertThat(resendResp.getStatusCode().value()).isEqualTo(200);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/emails")));
    }

    @Test
    @DisplayName("중복 이메일 → 409")
    void signup_duplicateEmail_returns409() {
        // First signup
        Map<String, Object> body = Map.of(
                "email", "duplicate@example.com",
                "password", "Password1",
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );
        restClient.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);

        // Second signup with same email
        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("필수 약관 미동의 → 422")
    void signup_requiredTermsNotAccepted_returns422() {
        Map<String, Object> body = Map.of(
                "email", "noterms@example.com",
                "password", "Password1",
                "consents", List.of(), // empty consents
                "ageConfirmed", true
        );

        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("연령 동의 미확인(ageConfirmed=false) → 422")
    void signup_ageNotConfirmed_returns422() {
        Map<String, Object> body = Map.of(
                "email", "underage@example.com",
                "password", "Password1",
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", false
        );

        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("비밀번호 정책 위반(숫자 없음) → 422")
    void signup_weakPassword_returns422() {
        Map<String, Object> body = Map.of(
                "email", "weakpass@example.com",
                "password", "onlyletters",  // no digits
                "consents", List.of(
                        Map.of("termsVersionId", serviceTermsId.toString(), "agreed", true),
                        Map.of("termsVersionId", privacyTermsId.toString(), "agreed", true)
                ),
                "ageConfirmed", true
        );

        assertThatThrownBy(() ->
                restClient.post().uri("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(422));
    }
}
