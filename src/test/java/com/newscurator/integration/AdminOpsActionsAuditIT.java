package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.domain.ExcludedKeyword;
import com.newscurator.repository.ArticleRepository;
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
 * 크라운주얼 ② 변형 ops 액션 감사(008 US3, 실 PG). 기사 hide/unhide, 제외 키워드 add/remove,
 * 요약 재시도 — 각 변형 액션이 같은 TX에서 AdminAuditLog 1건 + diff를 남김.
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
class AdminOpsActionsAuditIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_ops_audit_it")
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
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE admin_audit_log, excluded_keyword, article_keyword, articles, accounts"
                        + " RESTART IDENTITY CASCADE");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "ops-" + actorId + "@test.com");
    }

    private long article() {
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl("u/x").originalUrl("u/x").title("기사")
                        .publishedAt(now).firstCollectedAt(now).expiresAt(now.plusDays(90))
                        .build();
        return articleRepository.save(a).getId();
    }

    private long auditCount(String action, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_log WHERE action = ? AND target_id = ?",
                Long.class, action, targetId);
    }

    @Test
    @DisplayName("기사 hide/unhide — 각 audit 1건")
    void hideUnhide_audited() {
        long id = article();

        adminOpsService.hideArticle(actorId, id);
        assertThat(auditCount("ARTICLE_HIDE", String.valueOf(id))).isEqualTo(1);

        adminOpsService.unhideArticle(actorId, id);
        assertThat(auditCount("ARTICLE_UNHIDE", String.valueOf(id))).isEqualTo(1);
    }

    @Test
    @DisplayName("제외 키워드 add/remove — 각 audit 1건 + keyword diff")
    void excludedKeyword_audited() {
        ExcludedKeyword kw = adminOpsService.addExcludedKeyword(actorId, "광고");

        Long addRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action='EXCLUDED_KEYWORD_ADD'"
                                + " AND detail->>'keyword'='광고'",
                        Long.class);
        assertThat(addRows).isEqualTo(1L);

        adminOpsService.removeExcludedKeyword(actorId, kw.getId());
        assertThat(auditCount("EXCLUDED_KEYWORD_REMOVE", String.valueOf(kw.getId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("요약 재시도 — audit 1건 + before/after diff(FAILED→PENDING)")
    void summaryRetry_audited() {
        long id = article();
        // FAILED로 만들고 재시도
        Article a = articleRepository.findById(id).orElseThrow();
        a.failSummary();
        articleRepository.save(a);

        adminOpsService.retrySummary(actorId, id);

        Long rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action='SUMMARY_RETRY'"
                                + " AND target_id=? AND detail->>'before'='FAILED' AND detail->>'after'='PENDING'",
                        Long.class, String.valueOf(id));
        assertThat(rows).as("재시도 audit + diff(FAILED→PENDING)").isEqualTo(1L);
        // 실제 상태도 PENDING으로 전환
        assertThat(articleRepository.findById(id).orElseThrow().getSummaryStatus().name())
                .isEqualTo("PENDING");
    }
}
