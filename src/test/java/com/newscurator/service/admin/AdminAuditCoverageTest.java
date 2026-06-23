package com.newscurator.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.domain.ExcludedKeyword;
import com.newscurator.domain.Notice;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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
 * 크라운주얼 T072 감사 커버리지(008 FR-060, 실 PG). 변형(부수효과) 액션 전체를 실제로 1회씩 수행한 뒤,
 * 각 액션의 audit 행위 상수가 admin_audit_log에 빠짐없이 기록됐는지 <b>전수 단언</b>한다.
 *
 * <p>한 액션이라도 record() 호출을 누락하면 그 action 문자열이 audit 로그에 없어 containsAll이 깨진다 = 누락 색출.
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
class AdminAuditCoverageTest {

    /** 변형 액션 전체의 감사 action 상수 — 코드와 1:1. 새 변형 액션 추가 시 여기도 갱신. */
    private static final Set<String> EXPECTED_ACTIONS =
            Set.of(
                    "ROLE_CHANGE",
                    "ACCOUNT_STATUS_CHANGE",
                    "ARTICLE_HIDE",
                    "ARTICLE_UNHIDE",
                    "EXCLUDED_KEYWORD_ADD",
                    "EXCLUDED_KEYWORD_REMOVE",
                    "SUMMARY_RETRY",
                    "SCHEDULER_TOGGLE",
                    "SCHEDULER_RUN",
                    "NOTICE_CREATE",
                    "NOTICE_UPDATE",
                    "NOTICE_PUBLISH",
                    "NOTICE_DELETE",
                    "PUSH_SEND");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_audit_cov_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminUserService adminUserService;
    @Autowired private AdminOpsService adminOpsService;
    @Autowired private NoticeService noticeService;
    @Autowired private AdminPushService adminPushService;
    @Autowired private SchedulerControlService schedulerControlService;
    @Autowired private SchedulerManualRunService schedulerManualRunService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actor;
    private UUID userForRole;
    private UUID userForStatus;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE admin_audit_log, notification_outbox, notifications, device_tokens,"
                        + " excluded_keyword, notice, article_keyword, trend_keyword_slot, issue_snapshot,"
                        + " summaries, article_sources, articles, sources, accounts RESTART IDENTITY CASCADE");
        jdbcTemplate.update(
                "INSERT INTO scheduler_setting (scheduler_key) VALUES ('collection'),('trend_cleanup')"
                        + " ON CONFLICT (scheduler_key) DO NOTHING");
        actor = account("actor@test.com", "ADMIN");
        account("second-admin@test.com", "ADMIN"); // 마지막 ADMIN 가드 회피용
        userForRole = account("role@test.com", "USER");
        userForStatus = account("status@test.com", "USER");
    }

    private UUID account(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, status, signup_type) VALUES (?, ?, ?, 'ACTIVE', 'EMAIL')",
                id, email, role);
        return id;
    }

    private long failedSummaryArticle() {
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl("u/x").originalUrl("u/x").title("기사")
                        .publishedAt(now).firstCollectedAt(now).expiresAt(now.plusDays(90)).build();
        a.failSummary();
        return articleRepository.save(a).getId();
    }

    @Test
    @DisplayName("★ 변형 액션 전수 수행 → 14개 audit action 누락 0(containsAll)")
    void allMutatingActions_areAudited() {
        // US1
        adminUserService.changeRole(actor, userForRole, AccountRole.ADMIN); // ROLE_CHANGE
        adminUserService.changeStatus(actor, userForStatus, false); // ACCOUNT_STATUS_CHANGE
        // US3 ops
        long articleId = failedSummaryArticle();
        adminOpsService.hideArticle(actor, articleId); // ARTICLE_HIDE
        adminOpsService.unhideArticle(actor, articleId); // ARTICLE_UNHIDE
        adminOpsService.retrySummary(actor, articleId); // SUMMARY_RETRY
        ExcludedKeyword kw = adminOpsService.addExcludedKeyword(actor, "광고"); // EXCLUDED_KEYWORD_ADD
        adminOpsService.removeExcludedKeyword(actor, kw.getId()); // EXCLUDED_KEYWORD_REMOVE
        // US3 scheduler
        schedulerControlService.setEnabled("collection", false, actor); // SCHEDULER_TOGGLE
        schedulerManualRunService.runManually(actor, "trend_cleanup"); // SCHEDULER_RUN (외부 없는 작업)
        // US4 notice + push
        Notice n = noticeService.create(actor, "공지", "본문", false); // NOTICE_CREATE
        noticeService.update(actor, n.getId(), "공지2", "본문2"); // NOTICE_UPDATE
        noticeService.setPublished(actor, n.getId(), true); // NOTICE_PUBLISH
        noticeService.delete(actor, n.getId()); // NOTICE_DELETE
        adminPushService.sendCampaignPush(actor, "이벤트", "내용"); // PUSH_SEND

        List<String> recorded =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT action FROM admin_audit_log", String.class);

        assertThat(recorded)
                .as("변형 액션 14종 audit 누락 0")
                .containsAll(EXPECTED_ACTIONS);
    }
}
