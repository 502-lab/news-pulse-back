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
class ProfileIntegrationTest {

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
                    .withDatabaseName("newscurator_profile_it")
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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@profile.local");
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
        wireMock.stubFor(post(urlPathEqualTo("/send-verification-code"))
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
        return (String) ((Map<?, ?>) loginResp.get("tokens")).get("accessToken");
    }

    private void submitOnboarding(String token) {
        restClient.post().uri("/api/v1/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "nickname", "초기닉네임",
                        "ageGroup", "TWENTIES",
                        "categories", List.of("IT", "ECONOMY_FINANCE", "SCIENCE"),
                        "summaryDepth", "BRIEF",
                        "consumeMode", "READ",
                        "briefingTime", "07:00",
                        "timezoneOffset", 540,
                        "voiceEnabled", false,
                        "pushAgreed", false
                ))
                .retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("PUT /me/profile → GET /me/profile 라운드트립 DB 단언")
    void profile_putThenGet_roundTrip() {
        String token = signupAndGetToken("profile1@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "profile1@example.com");
        submitOnboarding(token);

        restClient.put().uri("/api/v1/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("nickname", "업데이트닉네임", "ageGroup", "THIRTIES", "occupation", "디자이너"))
                .retrieve().toBodilessEntity();

        String dbNickname = jdbcTemplate.queryForObject(
                "SELECT nickname FROM user_profiles WHERE account_id = ?::uuid", String.class, accountId);
        assertThat(dbNickname).isEqualTo("업데이트닉네임");

        Map<?, ?> getResp = restClient.get().uri("/api/v1/me/profile")
                .header("Authorization", "Bearer " + token)
                .retrieve().body(Map.class);
        assertThat(getResp.get("nickname")).isEqualTo("업데이트닉네임");
        assertThat(getResp.get("occupation")).isEqualTo("디자이너");
    }

    @Test
    @DisplayName("PUT /me/interests 카테고리 2개 → 422")
    void interests_twoCategories_returns422() {
        String token = signupAndGetToken("profile2@example.com");
        submitOnboarding(token);

        assertThatThrownBy(() -> restClient.put().uri("/api/v1/me/interests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("categories", List.of("IT", "ECONOMY_FINANCE")))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isIn(422, 400));
    }

    @Test
    @DisplayName("PUT /me/interests 비정본 카테고리(TECH/ECONOMY) → 422 VALIDATION_ERROR")
    void interests_invalidCategory_returns422() {
        String token = signupAndGetToken("profile_badcat@example.com");
        submitOnboarding(token);

        assertThatThrownBy(() -> restClient.put().uri("/api/v1/me/interests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("categories", List.of("TECH", "ECONOMY", "LIFESTYLE")))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getResponseBodyAsString()).contains("VALIDATION_ERROR");
                });
    }

    @Test
    @DisplayName("PUT /me/reading-preference → GET 라운드트립 DB 단언")
    void readingPreference_putThenGet_roundTrip() {
        String token = signupAndGetToken("profile3@example.com");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE LOWER(email) = ?", String.class, "profile3@example.com");
        submitOnboarding(token);

        restClient.put().uri("/api/v1/me/reading-preference")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("summaryDepth", "DEEP", "consumeMode", "LISTEN"))
                .retrieve().toBodilessEntity();

        String dbDepth = jdbcTemplate.queryForObject(
                "SELECT summary_depth FROM reading_preferences WHERE account_id = ?::uuid",
                String.class, accountId);
        assertThat(dbDepth).isEqualTo("DEEP");

        Map<?, ?> getResp = restClient.get().uri("/api/v1/me/reading-preference")
                .header("Authorization", "Bearer " + token)
                .retrieve().body(Map.class);
        assertThat(getResp.get("summaryDepth")).isEqualTo("DEEP");
        assertThat(getResp.get("consumeMode")).isEqualTo("LISTEN");
    }
}
