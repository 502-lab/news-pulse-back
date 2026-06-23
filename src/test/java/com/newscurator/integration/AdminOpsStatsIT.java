package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.dto.response.AuditLogItemResponse;
import com.newscurator.dto.response.ErrorLogResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.service.admin.AdminOpsStatsService;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 US5 심층 통계(008, 실 PG). ErrorLog FAILED 집계 / 감사 필터 / 빈값 안전.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {
            "app.client.gemini.api-key=test-key",
            "app.client.gemini.base-url=http://localhost:9999",
            "app.client.naver.client-id=test-id",
            "app.client.naver.client-secret=test-secret",
            "app.client.naver.base-url=http://localhost:9999",
            "app.scheduler.enabled=false"
        })
class AdminOpsStatsIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_opsstats_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminOpsStatsService opsStatsService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE notification_outbox, bias_analysis, summaries, article_sources,"
                        + " source_daily_usage, articles, sources, admin_audit_log, accounts"
                        + " RESTART IDENTITY CASCADE");
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                id, email);
        return id;
    }

    @Test
    @DisplayName("★ ErrorLog — summary/bias/outbox FAILED 출처별 집계")
    void errorLog_aggregatesFailedBySource() {
        UUID acct = account("a@test.com");

        // summary FAILED 기사 1
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl("u/1").originalUrl("u/1").title("기사")
                        .publishedAt(now).firstCollectedAt(now).expiresAt(now.plusDays(90)).build();
        a.failSummary();
        long articleId = articleRepository.save(a).getId();

        // bias FAILED 1 (article_id FK)
        jdbcTemplate.update(
                "INSERT INTO bias_analysis (article_id, status) VALUES (?, 'FAILED')", articleId);
        // outbox FAILED 1 (account_id FK)
        jdbcTemplate.update(
                "INSERT INTO notification_outbox (account_id, channel, status, payload, idempotency_key)"
                        + " VALUES (?, 'PUSH', 'FAILED', '{}'::jsonb, ?)",
                acct, "k-" + UUID.randomUUID());

        ErrorLogResponse err = opsStatsService.getErrorLog();
        assertThat(err.summaryFailed()).isEqualTo(1);
        assertThat(err.biasFailed()).isEqualTo(1);
        assertThat(err.notificationFailed()).isEqualTo(1);
        assertThat(err.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 DB — ErrorLog 전부 0, OpsStats/수집상세 빈 목록(NPE/분모0 없음)")
    void emptyDb_safe() {
        ErrorLogResponse err = opsStatsService.getErrorLog();
        assertThat(err.total()).isZero();
        assertThat(err.summaryFailed()).isZero();
        assertThat(err.biasFailed()).isZero();
        assertThat(err.notificationFailed()).isZero();

        assertThat(opsStatsService.getOpsStats(30)).isEmpty();
        assertThat(opsStatsService.getCollectionDetail(999L, 30)).isEmpty();
    }

    @Test
    @DisplayName("감사 로그 조회 — action/actor/기간 필터 정확성")
    void auditLog_filters() {
        UUID actorA = account("actorA@test.com");
        UUID actorB = account("actorB@test.com");

        // actorA: ROLE_CHANGE (어제), actorB: ARTICLE_HIDE (오늘)
        auditRow(actorA, "ROLE_CHANGE", "t1", "NOW() - INTERVAL '1 day'");
        auditRow(actorB, "ARTICLE_HIDE", "t2", "NOW()");
        auditRow(actorA, "ARTICLE_HIDE", "t3", "NOW()");

        // action 필터: ROLE_CHANGE 1건
        assertThat(opsStatsService.getAuditLogs("ROLE_CHANGE", null, null, null, PageRequest.of(0, 10)))
                .extracting(AuditLogItemResponse::action)
                .containsExactly("ROLE_CHANGE");

        // actor 필터: actorB → 1건(ARTICLE_HIDE)
        List<UUID> byActorB =
                opsStatsService
                        .getAuditLogs(null, actorB, null, null, PageRequest.of(0, 10))
                        .map(AuditLogItemResponse::actorAccountId)
                        .getContent();
        assertThat(byActorB).containsExactly(actorB);

        // 기간 필터: 최근 12시간 → 어제 ROLE_CHANGE 제외, 오늘 2건만
        var recent =
                opsStatsService.getAuditLogs(
                        null, null, java.time.Instant.now().minusSeconds(12 * 3600), null,
                        PageRequest.of(0, 10));
        assertThat(recent.getTotalElements()).isEqualTo(2);
    }

    private void auditRow(UUID actor, String action, String targetId, String createdAtExpr) {
        jdbcTemplate.update(
                "INSERT INTO admin_audit_log (actor_account_id, action, target_type, target_id, created_at)"
                        + " VALUES (?, ?, 'ACCOUNT', ?, " + createdAtExpr + ")",
                actor, action, targetId);
    }
}
