package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.repository.SchedulerSettingRepository;
import com.newscurator.service.admin.SchedulerControlService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
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
 * 크라운주얼 ③ SchedulerControlService(실 PG, 008 FR-031).
 *
 * <p>12키 default enabled=true 조회, 미존재 키도 true 기본, 토글 후 false 영속(재조회 유지) + 감사 1건.
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
class SchedulerControlServiceIT {

    private static final List<String> ALL_KEYS =
            List.of(
                    "collection", "ai_processing", "bias_analysis", "bias_recovery", "bias_sla",
                    "trend_aggregation", "trend_cleanup", "tts_processing",
                    "notification_outbox", "notification_expiry", "weekly_email", "expiry");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_sched_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private SchedulerControlService schedulerControlService;
    @Autowired private SchedulerSettingRepository settingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE admin_audit_log RESTART IDENTITY");
        // scheduler_setting은 V15 시드(12키) 유지 — 토글 후 복원
        jdbcTemplate.update("UPDATE scheduler_setting SET enabled = TRUE");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')"
                        + " ON CONFLICT (id) DO NOTHING",
                actorId, "sched-" + actorId + "@test.com");
    }

    @Test
    @DisplayName("12키 default enabled=true + 미존재 키도 true 기본")
    void isEnabled_all12True_andUnknownDefaultsTrue() {
        for (String key : ALL_KEYS) {
            assertThat(schedulerControlService.isEnabled(key)).as("%s default true", key).isTrue();
        }
        assertThat(schedulerControlService.isEnabled("nonexistent_key"))
                .as("행 부재 키 → 기본 true")
                .isTrue();
    }

    @Test
    @DisplayName("★ 토글 비활성 후 false 영속(재조회 유지) + 감사 1건")
    void setEnabled_false_persistsAndAudits() {
        schedulerControlService.setEnabled("trend_aggregation", false, actorId);

        // 서비스 재조회
        assertThat(schedulerControlService.isEnabled("trend_aggregation")).isFalse();
        // 리포지토리 직접 재조회(영속 확인 — 재기동 시뮬레이션)
        assertThat(settingRepository.findBySchedulerKey("trend_aggregation").orElseThrow().isEnabled())
                .as("DB 영속 false")
                .isFalse();
        // 다른 키는 영향 없음
        assertThat(schedulerControlService.isEnabled("collection")).isTrue();
        // 감사 1건(SCHEDULER_TOGGLE)
        Long auditRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action = 'SCHEDULER_TOGGLE'"
                                + " AND target_id = 'trend_aggregation'",
                        Long.class);
        assertThat(auditRows).isEqualTo(1L);
    }
}
