package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
import java.nio.file.Path;
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
class OnboardingIntegrationTest {

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
                    .withDatabaseName("newscurator_onboarding_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@onboarding.local");
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
                "TRUNCATE TABLE briefing_settings, reading_preferences, follow_keywords, user_interests, " +
                "user_profiles, consent_records, verification_codes, refresh_tokens, accounts RESTART IDENTITY CASCADE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    // ─── helpers ───

    /**
     * Signs up, sets email_verified=true in DB, then logs in to get a fresh token
     * with emailVerified=true in the JWT — needed for /me/** access.
     */
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

        // Bypass email verification for test purposes
        jdbcTemplate.update("UPDATE accounts SET email_verified = TRUE WHERE LOWER(email) = ?",
                email.toLowerCase());

        Map<?, ?> loginResp = restClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "Password1!"))
                .retrieve().body(Map.class);
        return (String) ((Map<?, ?>) ((Map<?, ?>) loginResp.get("data")).get("tokens")).get("accessToken");
    }

    private Map<String, Object> fullOnboardingBody() {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("nickname", "TestUser");
        body.put("ageGroup", "THIRTIES");
        body.put("occupation", "개발자");
        body.put("categories", List.of("IT", "ECONOMY_FINANCE", "SCIENCE"));
        body.put("keywords", List.of(Map.of("keyword", "삼성전자", "type", "COMPANY")));
        body.put("summaryDepth", "BALANCED");
        body.put("consumeMode", "READ");
        body.put("briefingTime", "08:00");
        body.put("timezoneOffset", 540);
        body.put("voiceEnabled", false);
        body.put("pushAgreed", false);
        return body;
    }

    // ─── tests ───

    @Test
    @DisplayName("정상 온보딩 → 5개 하위 엔티티 DB 저장 + personalizationActive=true")
    void onboarding_normal_savesAllEntitiesAndActivatesPersonalization() {
        String token = signupAndGetToken("onboard1@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "onboard1@example.com");

        restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fullOnboardingBody())
                .retrieve().toBodilessEntity();

        // user_profiles
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE account_id = ?::uuid", Integer.class, accountId);
        assertThat(profileCount).isEqualTo(1);

        // user_interests (3개)
        Integer interestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_interests WHERE account_id = ?::uuid", Integer.class, accountId);
        assertThat(interestCount).isEqualTo(3);

        // follow_keywords (1개)
        Integer keywordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow_keywords WHERE account_id = ?::uuid", Integer.class, accountId);
        assertThat(keywordCount).isEqualTo(1);

        // reading_preferences
        Integer rpCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reading_preferences WHERE account_id = ?::uuid", Integer.class, accountId);
        assertThat(rpCount).isEqualTo(1);

        // briefing_settings
        Integer bsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM briefing_settings WHERE account_id = ?::uuid", Integer.class, accountId);
        assertThat(bsCount).isEqualTo(1);

        // personalization_active = true (categories >= 3)
        Boolean personalizationActive = jdbcTemplate.queryForObject(
                "SELECT personalization_active FROM accounts WHERE id = ?::uuid", Boolean.class, accountId);
        assertThat(personalizationActive).isTrue();

        // onboarding_completed = true
        Boolean onboardingCompleted = jdbcTemplate.queryForObject(
                "SELECT onboarding_completed FROM accounts WHERE id = ?::uuid", Boolean.class, accountId);
        assertThat(onboardingCompleted).isTrue();
    }

    @Test
    @DisplayName("관심 카테고리 2개 → 422")
    void onboarding_twoCategories_returns422() {
        String token = signupAndGetToken("onboard2@example.com");

        Map<String, Object> body = Map.of(
                "categories", List.of("IT", "ECONOMY_FINANCE"),
                "summaryDepth", "BALANCED",
                "consumeMode", "READ",
                "briefingTime", "08:00",
                "timezoneOffset", 540,
                "voiceEnabled", false,
                "pushAgreed", false
        );

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isIn(422, 400));
    }

    @Test
    @DisplayName("비정본 카테고리(TECH/ECONOMY) 포함 → 422 VALIDATION_ERROR")
    void onboarding_invalidCategory_returns422() {
        String token = signupAndGetToken("onboard_badcat@example.com");

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("categories", List.of("TECH", "ECONOMY", "SCIENCE")); // 비정본 값
        body.put("summaryDepth", "BALANCED");
        body.put("consumeMode", "READ");
        body.put("briefingTime", "08:00");
        body.put("timezoneOffset", 540);
        body.put("voiceEnabled", false);
        body.put("pushAgreed", false);

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getResponseBodyAsString()).contains("VALIDATION_ERROR");
                });
    }

    @Test
    @DisplayName("온보딩 미완료 상태에서 재진입 → 200 (비차단)")
    void onboarding_reEntryBeforeCompleted_succeeds() {
        String token = signupAndGetToken("onboard3@example.com");

        // First submit
        restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fullOnboardingBody())
                .retrieve().toBodilessEntity();

        // Re-submit (idempotent)
        int status = restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fullOnboardingBody())
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("pushAgreed=true → push_agreed_at 기록됨")
    void onboarding_pushAgreed_recordsTimestamp() {
        String token = signupAndGetToken("onboard4@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "onboard4@example.com");

        Map<String, Object> body = Map.of(
                "categories", List.of("IT", "ECONOMY_FINANCE", "SCIENCE"),
                "summaryDepth", "BALANCED",
                "consumeMode", "READ",
                "briefingTime", "08:00",
                "timezoneOffset", 540,
                "voiceEnabled", false,
                "pushAgreed", true
        );

        restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();

        Object pushAgreedAt = jdbcTemplate.queryForObject(
                "SELECT push_agreed_at FROM briefing_settings WHERE account_id = ?::uuid",
                Object.class, accountId);
        assertThat(pushAgreedAt).isNotNull();
    }
}
