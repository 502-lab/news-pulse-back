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
 * T043: 트렌드 공개 접근 — 실제 SecurityConfig 로딩(@SpringBootTest, standalone 아님).
 *
 * <p>FR-013: /api/v1/trends/** 는 permitAll. US1~US5 전부 구현 완료 →
 * 5개 trends 엔드포인트(top5·wordcloud·heatmap·wow·issues) 모두 JWT 없이 200(빈 DB면 빈 목록).
 * 대조: 인증 필요 엔드포인트(/api/v1/bias/spectrum)는 JWT 없이 401(보안 필터 활성 증명).
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
    @DisplayName("JWT 없이 5개 trends 엔드포인트 전부 200 (공개, 빈 DB면 빈 목록)")
    void allFiveTrendEndpoints_public_200() {
        // US1~US5 전부 구현 → 5개 모두 permitAll + 빈 DB에서 빈 목록 200
        for (String path : new String[] {
                "/api/v1/trends/keywords/top5", // US1
                "/api/v1/trends/wow",           // US4
                "/api/v1/trends/heatmap",       // US3
                "/api/v1/trends/wordcloud",     // US3
                "/api/v1/trends/issues"}) {     // US5
            assertThat(statusOf(path))
                    .as("public trends endpoint %s → 200", path)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("대조: 인증 필요 엔드포인트는 JWT 없이 401 (보안 필터 활성)")
    void protectedEndpoint_401() {
        // /api/v1/bias/spectrum (006, 인증 필수) → permitAll 아님 → 401
        assertThat(statusOf("/api/v1/bias/spectrum")).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
