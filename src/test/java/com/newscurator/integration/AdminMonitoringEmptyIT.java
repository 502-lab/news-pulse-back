package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.dto.response.AdminKpiResponse;
import com.newscurator.service.admin.AdminMonitoringService;
import com.newscurator.testutil.BigmPostgresImage;
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
 * 크라운주얼 ① 빈값 안전(008 US2 SC-007, 실 PG). empty DB에서 KPI·수집량·스케줄러·bias/trend 뷰가
 * NPE·div-by-zero 없이 0/빈목록 반환(특히 완료율류 분모 0 가드).
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
class AdminMonitoringEmptyIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_mon_empty_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminMonitoringService monitoringService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyAll() {
        // accounts CASCADE는 scheduler_setting.updated_by FK로 시드까지 연쇄 삭제 → 아래서 재시드
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, article_keyword, trend_keyword_slot, issue_snapshot,"
                        + " source_daily_usage, summaries, article_sources, articles, sources,"
                        + " admin_audit_log, accounts RESTART IDENTITY CASCADE");
        reseedSchedulers();
    }

    /** CASCADE로 지워진 scheduler_setting 12키 시드 복원(V15와 동일). */
    private void reseedSchedulers() {
        jdbcTemplate.update(
                "INSERT INTO scheduler_setting (scheduler_key) VALUES"
                        + " ('collection'),('ai_processing'),('bias_analysis'),('bias_recovery'),('bias_sla'),"
                        + " ('trend_aggregation'),('trend_cleanup'),('tts_processing'),"
                        + " ('notification_outbox'),('notification_expiry'),('weekly_email'),('expiry')"
                        + " ON CONFLICT (scheduler_key) DO NOTHING");
    }

    @Test
    @DisplayName("empty DB — KPI 전부 0, 완료율 0.0(분모 0 가드, NPE/div-by-zero 없음)")
    void kpi_emptyDb_allZero() {
        AdminKpiResponse kpi = monitoringService.getKpi();
        assertThat(kpi.totalUsers()).isZero();
        assertThat(kpi.activeUsers()).isZero();
        assertThat(kpi.totalArticles()).isZero();
        assertThat(kpi.summaryCompletionRate()).isEqualTo(0.0);
        assertThat(kpi.biasCompletionRate()).isEqualTo(0.0);
        assertThat(kpi.trendIssueCount()).isZero();
    }

    @Test
    @DisplayName("empty DB — 수집량 빈 목록, bias/trend 뷰 0")
    void views_emptyDb_emptyOrZero() {
        assertThat(monitoringService.getCollectionVolume(7)).isEmpty();

        var bias = monitoringService.getBiasView();
        assertThat(bias.total()).isZero();
        assertThat(bias.done()).isZero();
        assertThat(bias.analyzedArticlesIncludingHidden()).isZero();

        var trend = monitoringService.getTrendView();
        assertThat(trend.issueCount()).isZero();
        assertThat(trend.keywordedArticlesIncludingHidden()).isZero();
    }

    @Test
    @DisplayName("empty DB여도 스케줄러 상태는 V15 시드 12키 반환(빈 목록 아님)")
    void schedulers_seeded12() {
        assertThat(monitoringService.getSchedulerStatuses()).hasSize(12);
    }
}
