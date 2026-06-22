package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * T045: 보존 정리(FR-009) 통합 테스트 (실 PostgreSQL).
 *
 * <p>90일 경과 슬롯/이슈 스냅샷은 삭제, 보존창 내(최신) 데이터는 영향 없음을 검증.
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
            "app.scheduler.enabled=false",
            "app.trend.retention-days=90"
        })
class TrendRetentionIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_retention_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TrendAggregationService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE trend_keyword_slot, issue_snapshot RESTART IDENTITY");
    }

    @Test
    @DisplayName("보존 정리: 90일 경과 슬롯/이슈 삭제, 보존창 내 최신은 불변")
    void cleanup_deletesOld_keepsRecent() {
        // 슬롯: 100일 경과(만료) + 1일 경과(보존)
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (NOW() - INTERVAL '100 days', 'ECONOMY', '만료키워드', 5, NOW())
                """);
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (NOW() - INTERVAL '1 day', 'ECONOMY', '최신키워드', 7, NOW())
                """);
        // 이슈 스냅샷: 100일 경과(만료) + 방금(보존)
        jdbcTemplate.update("""
                INSERT INTO issue_snapshot (derived_at, clustering_method, delta, keywords, article_ids)
                VALUES (NOW() - INTERVAL '100 days', 'CO_OCCURRENCE', 1.0, ARRAY['만료이슈'], ARRAY[1]::bigint[])
                """);
        jdbcTemplate.update("""
                INSERT INTO issue_snapshot (derived_at, clustering_method, delta, keywords, article_ids)
                VALUES (NOW(), 'CO_OCCURRENCE', 2.0, ARRAY['최신이슈'], ARRAY[2]::bigint[])
                """);

        assertThat(slotCount()).isEqualTo(2);
        assertThat(issueCount()).isEqualTo(2);

        // ── 보존 정리 실행 ──
        service.cleanup();

        // 만료분 삭제, 최신분 유지
        assertThat(slotCount()).as("90일 경과 슬롯 삭제").isEqualTo(1);
        assertThat(issueCount()).as("90일 경과 이슈 삭제").isEqualTo(1);
        assertThat(termExists("최신키워드")).isTrue();
        assertThat(termExists("만료키워드")).isFalse();

        Long recentIssue = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE '최신이슈' = ANY(keywords)", Long.class);
        Long expiredIssue = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE '만료이슈' = ANY(keywords)", Long.class);
        assertThat(recentIssue).isEqualTo(1L);
        assertThat(expiredIssue).isZero();
    }

    private long slotCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trend_keyword_slot", Long.class);
    }

    private long issueCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issue_snapshot", Long.class);
    }

    private boolean termExists(String term) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trend_keyword_slot WHERE term = ?", Long.class, term);
        return n != null && n > 0;
    }
}
