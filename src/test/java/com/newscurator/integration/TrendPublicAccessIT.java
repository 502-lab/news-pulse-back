package com.newscurator.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T043(US1 부분): 트렌드 공개 접근 — 실제 SecurityConfig 로딩(@SpringBootTest, standalone 아님).
 *
 * <p>FR-013: /api/v1/trends/** 는 permitAll. US1 슬라이스에선 top5만 구현 →
 * top5=200, 미구현 trends 경로=404(401/403 아님 → permitAll 서브트리 입증).
 * 대조: 인증 필요 엔드포인트는 JWT 없이 401(보안 필터 활성 증명).
 * (US3/4/5 구현 후 나머지 4개도 200으로 강화 — T043 Polish.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class TrendPublicAccessIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_trend_public_it")
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

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** 인증 헤더 없이 GET 요청 후 HTTP 상태 반환(2xx면 200). 4xx/5xx 모두 포착. */
    private int statusOf(String path) {
        try {
            client().get().uri(path).retrieve().toBodilessEntity();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    @DisplayName("JWT 없이 Top5 → 200 (공개)")
    void top5_public_200() {
        assertThat(statusOf("/api/v1/trends/keywords/top5")).isEqualTo(200);
    }

    @Test
    @DisplayName("JWT 없이 미구현 trends 경로 → 401/403 아님(permitAll 서브트리 통과)")
    void otherTrendPaths_notAuthBlocked() {
        // 미구현 → 404 또는 500(no-handler catch-all). 핵심: 401(미인증)/403(권한)이 아니어야
        // permitAll 매처가 서브트리를 덮음(보안 필터를 통과해 디스패처까지 도달).
        for (String path : new String[] {
                "/api/v1/trends/wordcloud",
                "/api/v1/trends/heatmap",
                "/api/v1/trends/wow",
                "/api/v1/trends/issues"}) {
            int st = statusOf(path);
            assertThat(st)
                    .as("path %s should pass permitAll (not 401/403)", path)
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
        }
    }

    @Test
    @DisplayName("대조: 인증 필요 엔드포인트는 JWT 없이 401 (보안 필터 활성)")
    void protectedEndpoint_401() {
        // /api/v1/bias/spectrum (006, 인증 필수) → permitAll 아님 → 401
        assertThat(statusOf("/api/v1/bias/spectrum")).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
