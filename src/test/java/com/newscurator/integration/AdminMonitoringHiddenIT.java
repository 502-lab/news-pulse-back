package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.dto.response.SchedulerStatusResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.service.admin.AdminMonitoringService;
import com.newscurator.service.admin.SchedulerControlService;
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
 * 크라운주얼 ② admin hidden 포함 조회 + ③ 스케줄러 상태(토글 반영) (008 US2, 실 PG).
 *
 * <p>★ admin_hidden_at이 설정된 기사라도 어드민 bias/trend 뷰에는 포함된다(일반 사용자 경로와 반대 방향).
 * 스케줄러 상태는 토글(US1 SchedulerControlService) 반영.
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
class AdminMonitoringHiddenIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_mon_hidden_it")
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
    @Autowired private SchedulerControlService schedulerControlService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // accounts CASCADE는 scheduler_setting.updated_by FK로 시드까지 연쇄 삭제 → 아래서 재시드
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, article_keyword, trend_keyword_slot, issue_snapshot,"
                        + " source_daily_usage, summaries, article_sources, articles, sources,"
                        + " admin_audit_log, accounts RESTART IDENTITY CASCADE");
        jdbcTemplate.update(
                "INSERT INTO scheduler_setting (scheduler_key) VALUES"
                        + " ('collection'),('ai_processing'),('bias_analysis'),('bias_recovery'),('bias_sla'),"
                        + " ('trend_aggregation'),('trend_cleanup'),('tts_processing'),"
                        + " ('notification_outbox'),('notification_expiry'),('weekly_email'),('expiry')"
                        + " ON CONFLICT (scheduler_key) DO NOTHING");
        jdbcTemplate.update("UPDATE scheduler_setting SET enabled = TRUE");
    }

    /** admin_hidden_at이 설정된(숨김) 기사 1건 + DONE 편향 + 키워드 1건 생성. */
    private long hiddenArticleWithBiasAndKeyword() {
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl("u/hidden")
                        .originalUrl("u/hidden")
                        .title("숨김 기사")
                        .publishedAt(now)
                        .firstCollectedAt(now)
                        .expiresAt(now.plusDays(90))
                        .build();
        a.hideByAdmin(now); // ★ 숨김
        Article saved = articleRepository.save(a);
        long id = saved.getId();
        jdbcTemplate.update(
                "INSERT INTO bias_analysis (article_id, status) VALUES (?, 'DONE')", id);
        jdbcTemplate.update(
                "INSERT INTO article_keyword (article_id, term, created_at) VALUES (?, '금리', NOW())",
                id);
        return id;
    }

    @Test
    @DisplayName("★ 숨김 기사도 어드민 bias 뷰에 포함(admin_hidden_at 필터 안 함)")
    void biasView_includesHiddenArticle() {
        hiddenArticleWithBiasAndKeyword();

        var bias = monitoringService.getBiasView();
        assertThat(bias.done()).isEqualTo(1);
        assertThat(bias.analyzedArticlesIncludingHidden())
                .as("숨김 기사의 분석도 어드민 뷰엔 집계")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 숨김 기사도 어드민 trend 뷰에 포함(키워드 추출 기사 수)")
    void trendView_includesHiddenArticle() {
        hiddenArticleWithBiasAndKeyword();

        var trend = monitoringService.getTrendView();
        assertThat(trend.keywordedArticlesIncludingHidden())
                .as("숨김 기사의 키워드도 어드민 뷰엔 집계")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("③ 스케줄러 상태 — 12키 반환 + 토글(collection 비활성) 반영")
    void schedulerStatus_reflectsToggle() {
        UUID actor = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actor, "mon-" + actor + "@test.com");

        schedulerControlService.setEnabled("collection", false, actor);

        var statuses = monitoringService.getSchedulerStatuses();
        assertThat(statuses).hasSize(12);
        SchedulerStatusResponse collection =
                statuses.stream()
                        .filter(s -> s.schedulerKey().equals("collection"))
                        .findFirst()
                        .orElseThrow();
        assertThat(collection.enabled()).as("토글 반영 → collection 비활성").isFalse();
        // 다른 키는 활성 유지
        assertThat(
                        statuses.stream()
                                .filter(s -> s.schedulerKey().equals("trend_aggregation"))
                                .findFirst()
                                .orElseThrow()
                                .enabled())
                .isTrue();
    }
}
