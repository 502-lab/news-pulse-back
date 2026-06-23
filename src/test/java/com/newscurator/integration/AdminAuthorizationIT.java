package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.testutil.BigmPostgresImage;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 T071 어드민 인가(008, 실 SecurityConfig, @SpringBootTest RANDOM_PORT).
 *
 * <p>5개 admin 영역(users·monitoring·ops(수집제어)·notices·ops-stats) 대표 엔드포인트 — JWT 없이 401,
 * USER role 403, ADMIN 200. 대조: 공개 GET /api/v1/notices는 인증 없이 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AdminAuthorizationIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_admin_authz_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", () -> "http://localhost:9999");
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", () -> "http://localhost:9999");
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;

    private RestClient client;

    // 5개 admin 영역 대표 엔드포인트(전부 GET read — 200 확인 단순화)
    private static final String[] ADMIN_ENDPOINTS = {
        "/api/v1/admin/users", // 사용자 관리
        "/api/v1/admin/monitoring/kpi", // 모니터링
        "/api/v1/admin/excluded-keywords", // 수집 제어(ops)
        "/api/v1/admin/notices", // 공지
        "/api/v1/admin/ops/errors" // 심층 통계
    };

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE refresh_tokens, accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        seedAccount("admin@authz.local", "ADMIN");
        seedAccount("user@authz.local", "USER");
    }

    private void seedAccount(String email, String role) {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, ?, 'ACTIVE', true, 'EMAIL')",
                email, ENCODER.encode(PW), role);
    }

    private String token(String email) {
        Map<?, ?> resp =
                client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", email, "password", PW))
                        .retrieve()
                        .body(Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        return (String) ((Map<?, ?>) data.get("tokens")).get("accessToken");
    }

    private int status(String path, String bearer) {
        try {
            var req = client.get().uri(path);
            if (bearer != null) {
                req = req.header("Authorization", "Bearer " + bearer);
            }
            req.retrieve().toBodilessEntity();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    @DisplayName("★ 5개 admin 영역 — JWT 없이 401, USER 403, ADMIN 200")
    void adminEndpoints_401_403_200() {
        String adminToken = token("admin@authz.local");
        String userToken = token("user@authz.local");

        for (String path : ADMIN_ENDPOINTS) {
            assertThat(status(path, null)).as("%s 미인증 → 401", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(status(path, userToken)).as("%s USER → 403", path)
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(status(path, adminToken)).as("%s ADMIN → 200", path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("대조: 공개 공지 GET /api/v1/notices는 인증 없이 200")
    void publicNotices_noAuth_200() {
        assertThat(status("/api/v1/notices", null)).isEqualTo(200);
    }
}
