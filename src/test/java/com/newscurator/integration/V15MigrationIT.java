package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 ② V15 마이그레이션(실 PG, Flyway 적용 후 스키마 검증).
 *
 * <p>4 테이블 + articles.admin_hidden_at + scheduler_setting 12행(정확히 12키) + FK(accounts.id UUID) 정합.
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
class V15MigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_v15_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    private boolean tableExists(String table) {
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                        Long.class,
                        table);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                        Long.class,
                        table,
                        column);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("4 신규 테이블 + articles.admin_hidden_at 컬럼 존재")
    void schema_tablesAndColumn_present() {
        assertThat(tableExists("notice")).isTrue();
        assertThat(tableExists("admin_audit_log")).isTrue();
        assertThat(tableExists("scheduler_setting")).isTrue();
        assertThat(tableExists("excluded_keyword")).isTrue();
        assertThat(columnExists("articles", "admin_hidden_at")).isTrue();
    }

    @Test
    @DisplayName("scheduler_setting 정확히 12행(12키 each) + 전부 enabled=true 시드")
    void schedulerSetting_seeded12Keys() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scheduler_setting", Long.class);
        assertThat(count).isEqualTo(12L);

        List<String> keys =
                jdbcTemplate.queryForList(
                        "SELECT scheduler_key FROM scheduler_setting ORDER BY scheduler_key", String.class);
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "collection", "ai_processing", "bias_analysis", "bias_recovery", "bias_sla",
                        "trend_aggregation", "trend_cleanup", "tts_processing",
                        "notification_outbox", "notification_expiry", "weekly_email", "expiry");

        Long enabledCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM scheduler_setting WHERE enabled = TRUE", Long.class);
        assertThat(enabledCount).as("시드는 전부 enabled=true").isEqualTo(12L);
    }

    @Test
    @DisplayName("FK 정합: accounts.id(UUID) 참조 — 존재하는 계정 OK, 미존재 UUID는 FK 위반")
    void fk_accountsUuid_enforced() {
        UUID actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "fk-" + actorId + "@test.com");

        // 존재하는 계정 참조 → 성공
        jdbcTemplate.update(
                "INSERT INTO notice (title, content, published, author_account_id) VALUES ('t','c',false,?)",
                actorId);

        // 미존재 UUID 참조 → FK 위반
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO admin_audit_log (actor_account_id, action, target_type)"
                                                + " VALUES (?, 'X', 'ACCOUNT')",
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
