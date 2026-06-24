package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.service.TrendAggregationService;
import com.newscurator.service.admin.AdminOpsService;
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
 * 크라운주얼 ② 제외 키워드 적용(008 FR-032/T055, 실 PG).
 *
 * <p>제외 키워드 등록 → 트렌드 추출 실행 → 그 term이 article_keyword/trend_keyword_slot에 미존재.
 * discriminating: 제외 안 한 다른 term(규제)은 잡힌다(제외 적용 안 하면 광고도 잡혔을 것).
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
class ExcludedKeywordApplicationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_exckw_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminOpsService adminOpsService;
    @Autowired private TrendAggregationService trendAggregationService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, issue_snapshot, summaries,"
                        + " article_sources, articles, sources, excluded_keyword, admin_audit_log,"
                        + " accounts RESTART IDENTITY CASCADE");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "kw-" + actorId + "@test.com");
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

    private long keywordRows(String term) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_keyword WHERE term = ?", Long.class, term);
    }

    private long slotRows(String term) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trend_keyword_slot WHERE term = ?", Long.class, term);
    }

    @Test
    @DisplayName("★ 제외 키워드는 추출/슬롯에서 빠짐(광고 제외, 규제는 잡힘 — discriminating)")
    void excludedKeyword_notExtracted() {
        completedArticle("u/1", "광고 규제 발표", "광고 규제 강화 발표 정부");
        completedArticle("u/2", "광고 규제 시행", "광고 규제 시행 발표 정부");

        // '광고' 제외 등록
        adminOpsService.addExcludedKeyword(actorId, "광고");

        // 추출/집계 실행
        trendAggregationService.aggregate();

        // 광고: 제외되어 미존재
        assertThat(keywordRows("광고")).as("광고는 제외 → article_keyword 미존재").isZero();
        assertThat(slotRows("광고")).as("광고는 슬롯에도 미존재").isZero();

        // 규제: 제외 안 했으므로 존재(discriminating — 추출/적용 파이프라인이 살아있음 증명)
        assertThat(keywordRows("규제")).as("규제는 제외 안 함 → 존재").isGreaterThan(0);
    }
}
