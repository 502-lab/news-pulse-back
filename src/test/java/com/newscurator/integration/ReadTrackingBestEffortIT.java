package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.newscurator.service.ReadTrackingService;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 T011 — best-effort 격리(009 FR-003). recordView가 예외를 던져도 기사 상세는 정상(200)이고,
 * 상세의 lazy write(deep 요약 save)는 롤백되지 않으며, 조회 이벤트는 기록되지 않는다(예외 삼킴).
 *
 * <p>검증 = <b>end-to-end 격리 결과</b>. 격리 보장의 핵심은 post-commit 호출 순서(getDetail @Transactional이
 * 컨트롤러 반환 시 커밋된 뒤 recordView 호출) + 컨트롤러 try-catch다(REQUIRES_NEW는 방어적 경계).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ReadTrackingBestEffortIT {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PW = "Password1!";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_read_besteffort_it")
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

    // 실패 주입: recordView가 예외를 던지도록 — 상세가 영향받지 않음을 증명
    @MockitoBean private ReadTrackingService readTrackingService;

    private RestClient client;
    private long articleId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, refresh_tokens, summaries, article_sources, articles,"
                        + " accounts RESTART IDENTITY CASCADE");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', true, 'EMAIL')",
                "reader@besteffort.local", ENCODER.encode(PW));
        articleId = insertArticle();
        doThrow(new RuntimeException("boom")).when(readTrackingService).recordView(any(), any());
    }

    private long insertArticle() {
        String url = "https://besteffort.local/a";
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, published_at,"
                        + " first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, "best-effort 기사", now, now, now.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    private String token() {
        Map<?, ?> resp =
                client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "reader@besteffort.local", "password", PW))
                        .retrieve()
                        .body(Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        return (String) ((Map<?, ?>) data.get("tokens")).get("accessToken");
    }

    @Test
    @DisplayName("★ recordView 실패 → 상세 200 + 요약 lazy write 보존 + 조회이벤트 0(예외 삼킴)")
    void recordViewFailure_doesNotBreakDetail() {
        int status =
                client.get()
                        .uri("/api/v1/articles/" + articleId)
                        .header("Authorization", "Bearer " + token())
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value();

        // 1) 상세는 정상 200 (기록 실패가 핫패스를 안 깸)
        assertThat(status).isEqualTo(200);

        // 2) getDetail의 lazy write(deep 요약 save)가 롤백되지 않음 — DEEP summary 행 존재
        Long deepSummaries =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM summaries WHERE article_id = ? AND depth = 'DEEP'",
                        Long.class, articleId);
        assertThat(deepSummaries).isEqualTo(1L);

        // 3) recordView 예외는 삼켜짐 → 조회 이벤트 0
        Long events =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM article_event WHERE article_id = ?", Long.class, articleId);
        assertThat(events).isEqualTo(0L);
    }
}
