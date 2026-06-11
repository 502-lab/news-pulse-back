package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.TermsVersion;
import com.newscurator.repository.TermsVersionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SocialAuthIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_social_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_HASH = encoder.encode("Admin@test123!");

    /** EC P-256 key pair generated once for all Apple tests in this class. */
    private static ECPrivateKey applePrivateKey;
    private static ECPublicKey applePublicKey;
    private static String applePrivateKeyPem;

    @BeforeAll
    static void generateAppleKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var kp = kpg.generateKeyPair();
        applePrivateKey = (ECPrivateKey) kp.getPrivate();
        applePublicKey = (ECPublicKey) kp.getPublic();

        byte[] pkcs8 = applePrivateKey.getEncoded();
        applePrivateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pkcs8)
                + "\n-----END PRIVATE KEY-----";
    }

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
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@social.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);

        // OAuth: all providers point to WireMock
        registry.add("oauth.kakao.client-id", () -> "kakao-test-id");
        registry.add("oauth.kakao.client-secret", () -> "kakao-test-secret");
        registry.add("oauth.kakao.redirect-uri", () -> "http://localhost/callback");
        registry.add("oauth.kakao.token-base-url", wireMock::baseUrl);
        registry.add("oauth.kakao.api-base-url", wireMock::baseUrl);

        registry.add("oauth.google.client-id", () -> "google-test-id");
        registry.add("oauth.google.client-secret", () -> "google-test-secret");
        registry.add("oauth.google.redirect-uri", () -> "http://localhost/callback");
        registry.add("oauth.google.token-base-url", wireMock::baseUrl);

        registry.add("oauth.apple.client-id", () -> "apple.test.app");
        registry.add("oauth.apple.team-id", () -> "TESTTEAMID");
        registry.add("oauth.apple.key-id", () -> "TESTKEYID");
        registry.add("oauth.apple.redirect-uri", () -> "http://localhost/callback");
        registry.add("oauth.apple.token-base-url", wireMock::baseUrl);
        // Private key injected after @BeforeAll — use a lazy supplier
        registry.add("oauth.apple.private-key", () -> applePrivateKeyPem);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TermsVersionRepository termsVersionRepository;
    @Autowired private ObjectMapper objectMapper;
    @Value("${jwt.secret:default-test-secret-change-in-prod-minimum-32-chars!!}")
    private String jwtSecret;

    private RestClient restClient;
    private UUID serviceTermsId;
    private UUID privacyTermsId;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();

        jdbcTemplate.execute(
                "TRUNCATE TABLE social_connections, consent_records, verification_codes, "
                        + "refresh_tokens, accounts RESTART IDENTITY CASCADE");

        List<TermsVersion> activeTerms = termsVersionRepository.findByIsActiveTrue();
        serviceTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("SERVICE")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
        privacyTermsId = activeTerms.stream()
                .filter(t -> t.getType().name().equals("PRIVACY")).findFirst()
                .map(TermsVersion::getId).orElseThrow();
    }

    // ─── helpers ───

    private String generateOAuthState(String provider) {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        javax.crypto.SecretKey signingKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(provider)
                .claim("type", "OAUTH_STATE")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 5L * 60 * 1000))
                .signWith(signingKey)
                .compact();
    }

    private String buildGoogleIdToken(String sub, String email) {
        // Build a compact base64url-encoded fake id_token payload (no real signing needed for test)
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payloadJson = String.format("{\"sub\":\"%s\",\"email\":\"%s\"}", sub, email);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".fakesig";
    }

    private String buildAppleIdToken(String sub, String email) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"kid\":\"APPLEKEY\"}".getBytes());
        String payloadJson = String.format("{\"sub\":\"%s\",\"email\":\"%s\"}", sub, email);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".fakesig";
    }

    // ─── tests ───

    @Test
    @DisplayName("Kakao 신규 가입 → 201, emailVerified=true (DB 확인), SocialConnection 저장")
    void kakao_newUser_returns201AndEmailVerified() throws Exception {
        // Stub Kakao token endpoint
        wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"kakao-access-token\"}")));

        // Stub Kakao user info endpoint
        wireMock.stubFor(get(urlPathEqualTo("/v2/user/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":12345678,\"kakao_account\":{\"email\":\"kakao@example.com\"}}")));

        String state = generateOAuthState("KAKAO");
        var response = restClient.post().uri("/api/v1/auth/social/kakao/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "kakao-auth-code", "state", state))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<?, ?> body = response.getBody();
        assertThat(body.containsKey("tokens")).isTrue();
        assertThat((boolean) body.get("isNew")).isTrue();

        // DB: email_verified = true (FR-024)
        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM accounts WHERE LOWER(email) = ?",
                Boolean.class, "kakao@example.com");
        assertThat(emailVerified).isTrue();

        // DB: SocialConnection exists with providerUserId = "12345678"
        Integer connCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM social_connections WHERE provider = 'KAKAO' AND provider_user_id = '12345678'",
                Integer.class);
        assertThat(connCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Kakao nullable email (미동의) → 이메일 없이 계정 생성")
    void kakao_noEmail_createsAccountWithoutEmail() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"kakao-token-no-email\"}")));

        wireMock.stubFor(get(urlPathEqualTo("/v2/user/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":99991234,\"kakao_account\":{}}")));  // no email field

        String state = generateOAuthState("KAKAO");
        var response = restClient.post().uri("/api/v1/auth/social/kakao/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "kakao-code-noemail", "state", state))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);

        Integer connCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM social_connections WHERE provider = 'KAKAO' AND provider_user_id = '99991234'",
                Integer.class);
        assertThat(connCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Google 신규 가입 → 201, sub+email 파싱 단언")
    void google_newUser_returns201AndParsesSubAndEmail() throws Exception {
        String googleSub = "google-sub-" + UUID.randomUUID();
        String googleEmail = "google-" + UUID.randomUUID() + "@gmail.com";
        String idToken = buildGoogleIdToken(googleSub, googleEmail);

        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"google-at\",\"id_token\":\"" + idToken + "\"}")));

        String state = generateOAuthState("GOOGLE");
        var response = restClient.post().uri("/api/v1/auth/social/google/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "google-auth-code", "state", state))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);

        // DB: SocialConnection with correct providerUserId (= sub)
        Integer connCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM social_connections WHERE provider = 'GOOGLE' AND provider_user_id = ?",
                Integer.class, googleSub);
        assertThat(connCount).isEqualTo(1);

        // DB: account email = google email
        Integer accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE LOWER(email) = ?",
                Integer.class, googleEmail.toLowerCase());
        assertThat(accountCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Google 기존 소셜 → 200 로그인")
    void google_existingUser_returns200() throws Exception {
        String googleSub = "existing-google-sub";
        String googleEmail = "existing-google@gmail.com";
        String idToken = buildGoogleIdToken(googleSub, googleEmail);

        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"google-at\",\"id_token\":\"" + idToken + "\"}")));

        String state1 = generateOAuthState("GOOGLE");
        // First login → 201
        restClient.post().uri("/api/v1/auth/social/google/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "google-code-1", "state", state1))
                .retrieve().toBodilessEntity();

        // Second login → 200
        String state2 = generateOAuthState("GOOGLE");
        var response2 = restClient.post().uri("/api/v1/auth/social/google/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "google-code-2", "state", state2))
                .retrieve().toEntity(Map.class);

        assertThat(response2.getStatusCode().value()).isEqualTo(200);
        assertThat((boolean) response2.getBody().get("isNew")).isFalse();
    }

    @Test
    @DisplayName("state 위조 → 400 OAUTH_STATE_INVALID")
    void callback_forgedState_returns400() {
        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/social/kakao/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "any-code", "state", "forged.state.value"))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("state 만료 → 400 OAUTH_STATE_INVALID (리플레이 보호)")
    void callback_expiredState_returns400() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        javax.crypto.SecretKey signingKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes);
        long tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
        String expiredState = Jwts.builder()
                .subject("KAKAO")
                .claim("type", "OAUTH_STATE")
                .issuedAt(new Date(tenMinutesAgo - 5 * 60 * 1000L))
                .expiration(new Date(tenMinutesAgo))  // expired 10 minutes ago
                .signWith(signingKey)
                .compact();

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/social/kakao/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "any-code", "state", expiredState))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("state provider 불일치 → 400 OAUTH_STATE_INVALID")
    void callback_providerMismatch_returns400() {
        // State says GOOGLE but endpoint is kakao
        String state = generateOAuthState("GOOGLE");

        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/social/kakao/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "any-code", "state", state))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("이메일 충돌: 같은 이메일 이메일 계정 존재 → 409")
    void callback_emailConflict_returns409() throws Exception {
        // Create an email account first
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) "
                        + "VALUES ('conflict@example.com', 'hash', 'USER', 'ACTIVE', true, 'EMAIL')");

        // Try to sign up with Google using the same email
        String googleSub = "conflict-google-sub";
        String idToken = buildGoogleIdToken(googleSub, "conflict@example.com");

        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"google-at\",\"id_token\":\"" + idToken + "\"}")));

        String state = generateOAuthState("GOOGLE");
        assertThatThrownBy(() -> restClient.post().uri("/api/v1/auth/social/google/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "google-code-conflict", "state", state))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(409);
                    assertThat(((HttpClientErrorException) e).getResponseBodyAsString())
                            .contains("EMAIL_ALREADY_EXISTS");
                });
    }

    @Test
    @DisplayName("Apple client_secret은 ES256 서명 유효 JWT (iss/sub/aud/exp/kid 검증) + 신규 → 201 + emailVerified=true")
    void apple_clientSecret_isValidEs256Jwt() throws Exception {
        String appleSub = "apple-sub-" + UUID.randomUUID();
        String relayEmail = appleSub + "@privaterelay.appleid.com";
        String appleIdToken = buildAppleIdToken(appleSub, relayEmail);

        // Capture the client_secret from the token exchange request
        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"apple-at\",\"id_token\":\"" + appleIdToken + "\"}")));

        String state = generateOAuthState("APPLE");
        var callbackResponse = restClient.post().uri("/api/v1/auth/social/apple/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "apple-auth-code", "state", state))
                .retrieve().toEntity(Map.class);

        // 신규 → 201 (FR-024 implicit: social signup)
        assertThat(callbackResponse.getStatusCode().value()).isEqualTo(201);

        // DB: email_verified = true (FR-024)
        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM accounts WHERE LOWER(email) = ?",
                Boolean.class, relayEmail.toLowerCase());
        assertThat(emailVerified).isTrue();

        // Extract client_secret from captured WireMock request
        var serveEvent = wireMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().contains("/auth/token"))
                .findFirst().orElseThrow();

        String requestBody = serveEvent.getRequest().getBodyAsString();
        // Parse client_secret from form-encoded body
        String clientSecret = parseFormParam(requestBody, "client_secret");
        assertThat(clientSecret).isNotBlank();

        // Verify the client_secret JWT header has alg=ES256 and kid=TESTKEYID
        String[] parts = clientSecret.split("\\.");
        assertThat(parts).hasSize(3);
        String headerJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[0])));
        assertThat(headerJson).contains("\"ES256\"");
        assertThat(headerJson).contains("\"TESTKEYID\"");

        // Verify claims using the test public key
        var publicKeyBytes = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                jwtSecret.getBytes(StandardCharsets.UTF_8));  // won't work for EC; use raw JWT decode
        String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
        assertThat(payloadJson).contains("\"TESTTEAMID\"");  // iss
        assertThat(payloadJson).contains("\"apple.test.app\"");  // sub
        assertThat(payloadJson).contains("appleid.apple.com");  // aud
        assertThat(payloadJson).contains("\"exp\"");
        // Verify signature with the test EC public key
        Claims claims = Jwts.parser()
                .verifyWith(applePublicKey)
                .build()
                .parseSignedClaims(clientSecret)
                .getPayload();
        assertThat(claims.getIssuer()).isEqualTo("TESTTEAMID");
        assertThat(claims.getSubject()).isEqualTo("apple.test.app");
    }

    @Test
    @DisplayName("Apple relay email(@privaterelay.appleid.com) 그대로 저장 → 201")
    void apple_relayEmail_storedAsIs() throws Exception {
        String appleSub = "apple-relay-sub";
        String relayEmail = appleSub + "@privaterelay.appleid.com";
        String appleIdToken = buildAppleIdToken(appleSub, relayEmail);

        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"apple-at\",\"id_token\":\"" + appleIdToken + "\"}")));

        String state = generateOAuthState("APPLE");
        var response = restClient.post().uri("/api/v1/auth/social/apple/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "apple-relay-code", "state", state))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);

        // DB: provider_email stored as relay email (중계 이메일 원본 보존)
        String storedEmail = jdbcTemplate.queryForObject(
                "SELECT provider_email FROM social_connections WHERE provider = 'APPLE' AND provider_user_id = ?",
                String.class, appleSub);
        assertThat(storedEmail).isEqualTo(relayEmail);

        // DB: account email = relay email, emailVerified = true (FR-024)
        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM accounts WHERE LOWER(email) = ?",
                Boolean.class, relayEmail.toLowerCase());
        assertThat(emailVerified).isTrue();
    }

    @Test
    @DisplayName("Apple 최초 로그인 userJson → SocialConnection.user_info 저장 (002 스코프)")
    void apple_firstLogin_userInfoStoredInSocialConnection() throws Exception {
        String appleSub = "apple-userinfo-sub";
        String relayEmail = appleSub + "@privaterelay.appleid.com";
        String appleIdToken = buildAppleIdToken(appleSub, relayEmail);

        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"apple-at\",\"id_token\":\"" + appleIdToken + "\"}")));

        // Apple 최초 로그인: 클라이언트가 userJson(이름 등)을 POST body에 포함
        String userJson = "{\"name\":{\"firstName\":\"Gildong\",\"lastName\":\"Hong\"},\"email\":\"" + relayEmail + "\"}";
        String state = generateOAuthState("APPLE");
        restClient.post().uri("/api/v1/auth/social/apple/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "apple-userinfo-code", "state", state, "userJson", userJson))
                .retrieve().toBodilessEntity();

        // DB: social_connections.user_info = 전달된 userJson 그대로
        String storedUserInfo = jdbcTemplate.queryForObject(
                "SELECT user_info FROM social_connections WHERE provider = 'APPLE' AND provider_user_id = ?",
                String.class, appleSub);
        assertThat(storedUserInfo).isEqualTo(userJson);
    }

    private static String parseFormParam(String body, String paramName) {
        for (String part : body.split("&")) {
            if (part.startsWith(paramName + "=")) {
                return java.net.URLDecoder.decode(
                        part.substring(paramName.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String padBase64(String base64) {
        int pad = 4 - base64.length() % 4;
        if (pad < 4) base64 += "=".repeat(pad);
        return base64;
    }
}
