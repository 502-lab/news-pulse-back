package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.Article;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.service.admin.SchedulerControlService;
import com.newscurator.service.admin.SchedulerManualRunService;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 ① 스케줄러 수동 실행(008 FR-030/T048, 실 PG).
 *
 * <p>★ 게이트 우회: 토글로 비활성(disabled)한 스케줄러를 수동 실행하면 게이트를 우회해 실제 작업이
 * 수행됨 + SCHEDULER_RUN 감사 1건. (수동 실행이 게이트를 따랐다면 disabled라 작업이 안 됐을 것 — discriminating.)
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
class SchedulerManualRunIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_manualrun_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private SchedulerManualRunService manualRunService;
    @Autowired private SchedulerControlService schedulerControlService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, issue_snapshot, summaries,"
                        + " article_sources, articles, sources, admin_audit_log, accounts"
                        + " RESTART IDENTITY CASCADE");
        // accounts CASCADE가 scheduler_setting 시드까지 지움 → 재시드
        jdbcTemplate.update(
                "INSERT INTO scheduler_setting (scheduler_key) VALUES"
                        + " ('collection'),('ai_processing'),('bias_analysis'),('bias_recovery'),('bias_sla'),"
                        + " ('trend_aggregation'),('trend_cleanup'),('tts_processing'),"
                        + " ('notification_outbox'),('notification_expiry'),('weekly_email'),('expiry')"
                        + " ON CONFLICT (scheduler_key) DO NOTHING");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "mr-" + actorId + "@test.com");
    }

    private void completedArticle(String url, String title, String balanced) {
        OffsetDateTime collectedAt = OffsetDateTime.now().minusHours(2);
        Article a =
                Article.builder()
                        .normalizedUrl(url).originalUrl(url).title(title)
                        .publishedAt(collectedAt).firstCollectedAt(collectedAt)
                        .expiresAt(collectedAt.plusDays(90)).build();
        a.completeSummary();
        Article saved = articleRepository.save(a);
        jdbcTemplate.update(
                "INSERT INTO summaries (article_id, depth, status, content, ai_generated, created_at, updated_at)"
                        + " VALUES (?, 'BALANCED', 'COMPLETED', ?, true, NOW(), NOW())",
                saved.getId(), balanced);
    }

    @Test
    @DisplayName("★ disabled 스케줄러 수동 실행 → 게이트 우회로 실제 작업 수행 + audit 1건")
    void disabledScheduler_manualRun_bypassesGate() {
        completedArticle("u/1", "정부 금리 인상", "한국은행 금리 인상 발표 경제 영향");
        completedArticle("u/2", "금리 인상 지속", "시장 금리 인상 지속 경제 전망");

        // 토글로 비활성
        schedulerControlService.setEnabled("trend_aggregation", false, actorId);
        assertThat(schedulerControlService.isEnabled("trend_aggregation")).isFalse();

        long slotsBefore =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trend_keyword_slot", Long.class);
        assertThat(slotsBefore).isZero();

        // 수동 실행 — 게이트 우회로 aggregate 수행
        manualRunService.runManually(actorId, "trend_aggregation");

        long slotsAfter =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trend_keyword_slot", Long.class);
        assertThat(slotsAfter).as("disabled여도 수동 실행은 작업 수행(게이트 우회)").isGreaterThan(0);

        Long auditRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action='SCHEDULER_RUN'"
                                + " AND target_id='trend_aggregation'",
                        Long.class);
        assertThat(auditRows).as("수동 실행 audit 1건").isEqualTo(1L);
    }

    @Test
    @DisplayName("enabled 스케줄러도 수동 실행 가능")
    void enabledScheduler_manualRun_works() {
        completedArticle("u/1", "금리 인상", "금리 인상 경제");
        // enabled 유지(기본 true)
        manualRunService.runManually(actorId, "trend_aggregation");
        long slots =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trend_keyword_slot", Long.class);
        assertThat(slots).isGreaterThan(0);
    }

    @Test
    @DisplayName("알 수 없는 스케줄러 키 → 404")
    void unknownKey_throws() {
        assertThatThrownBy(() -> manualRunService.runManually(actorId, "no_such_key"))
                .isInstanceOf(AdminTargetNotFoundException.class);
    }
}
