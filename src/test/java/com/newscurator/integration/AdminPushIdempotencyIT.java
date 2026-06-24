package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Notice;
import com.newscurator.repository.NoticeRepository;
import com.newscurator.service.admin.AdminPushService;
import com.newscurator.testutil.BigmPostgresImage;
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
 * 크라운주얼 ① 어드민 푸시 dedup 멱등(008 FR-042/T063, 실 PG).
 *
 * <p>(a) 공지 푸시: 같은 noticeId 2회 발송 → 계정당 outbox 1건(키 ADMIN:NOTICE:{noticeId}:{accountId} 결정적).
 * (b) ★ discriminating: 캠페인 푸시는 매 발송 새 UUID → 2회 발송 시 계정당 2건. 공지(멱등) vs 캠페인(비멱등) 대비.
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
class AdminPushIdempotencyIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_push_idem_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminPushService adminPushService;
    @Autowired private NoticeRepository noticeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE notification_outbox, notifications, device_tokens, notice,"
                        + " admin_audit_log, accounts RESTART IDENTITY CASCADE");
        actorId = account("admin@test.com", false); // 관리자(토큰 없음 → 발송 대상서 자동 skip)
        account("user1@test.com", true); // 토큰 보유 → 발송 대상
        account("user2@test.com", true);
    }

    private UUID account(String email, boolean withToken) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, status, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', 'EMAIL')",
                id, email);
        if (withToken) {
            jdbcTemplate.update(
                    "INSERT INTO device_tokens (account_id, token, platform) VALUES (?, ?, 'ANDROID')",
                    id, "tok-" + id);
        }
        return id;
    }

    private long outboxLike(String keyPrefix) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE idempotency_key LIKE ?",
                Long.class, keyPrefix + "%");
    }

    @Test
    @DisplayName("(a) 공지 푸시 멱등 — 같은 noticeId 2회 발송 → 토큰 보유 계정당 1건(총 2)")
    void noticePush_idempotent() {
        Notice notice =
                noticeRepository.save(
                        Notice.builder().title("점검 공지").content("서버 점검").published(true)
                                .authorAccountId(actorId).build());

        adminPushService.sendNoticePush(actorId, notice.getId());
        adminPushService.sendNoticePush(actorId, notice.getId()); // 재발송

        assertThat(outboxLike("ADMIN:NOTICE:" + notice.getId()))
                .as("같은 공지 재발송은 멱등 → 토큰 보유 2계정당 1건")
                .isEqualTo(2L);

        // 발송 audit 2건(발송 행위 2회)
        Long auditRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action='PUSH_SEND'", Long.class);
        assertThat(auditRows).isEqualTo(2L);
    }

    @Test
    @DisplayName("★ (b) 캠페인 푸시 비멱등 — 2회 발송 → 매번 새 UUID라 계정당 2건(총 4)")
    void campaignPush_nonIdempotent() {
        adminPushService.sendCampaignPush(actorId, "이벤트", "할인 이벤트");
        adminPushService.sendCampaignPush(actorId, "이벤트", "할인 이벤트"); // 의도적 재발송

        assertThat(outboxLike("ADMIN:CAMPAIGN:"))
                .as("캠페인은 매 발송 새 campaignId → 2계정 × 2발송 = 4건")
                .isEqualTo(4L);
    }
}
