package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Notice;
import com.newscurator.service.admin.NoticeService;
import com.newscurator.testutil.BigmPostgresImage;
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
 * 크라운주얼 ②③ 공지 공개 노출 + 감사(008 US4, 실 PG).
 *
 * <p>게시(published=true)만 공개 노출, 초안은 제외 / admin은 전체. 토글 즉시 반영. CRUD 감사.
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
class NoticePublishIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_notice_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private NoticeService noticeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE notice, admin_audit_log, accounts RESTART IDENTITY CASCADE");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "n-" + actorId + "@test.com");
    }

    @Test
    @DisplayName("게시 공지만 공개 노출, 초안 제외 / admin은 전체 + 토글 즉시 반영")
    void publicListShowsPublishedOnly() {
        Notice published = noticeService.create(actorId, "게시 공지", "본문", true);
        Notice draft = noticeService.create(actorId, "초안 공지", "본문", false);

        // 공개: 게시된 것만
        assertThat(noticeService.listPublished()).extracting(Notice::getId).containsExactly(published.getId());
        // admin: 전체
        assertThat(noticeService.listAll(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);

        // 초안 게시 → 공개에 즉시 반영
        noticeService.setPublished(actorId, draft.getId(), true);
        assertThat(noticeService.listPublished()).extracting(Notice::getId)
                .containsExactlyInAnyOrder(published.getId(), draft.getId());

        // 게시 중단 → 공개에서 사라짐
        noticeService.setPublished(actorId, published.getId(), false);
        assertThat(noticeService.listPublished()).extracting(Notice::getId).containsExactly(draft.getId());
    }

    @Test
    @DisplayName("Notice CRUD 감사 — create/publish/delete 각 1건")
    void noticeCrud_audited() {
        Notice n = noticeService.create(actorId, "공지", "본문", false);
        noticeService.setPublished(actorId, n.getId(), true);
        noticeService.delete(actorId, n.getId());

        for (String action : new String[] {"NOTICE_CREATE", "NOTICE_PUBLISH", "NOTICE_DELETE"}) {
            Long rows =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM admin_audit_log WHERE action=? AND target_id=?",
                            Long.class, action, String.valueOf(n.getId()));
            assertThat(rows).as("%s 감사 1건", action).isEqualTo(1L);
        }
    }
}
