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
 * 크라운주얼 인앱 알림 멱등 + 토큰 없는 사용자 도달(008 FR-042 보강, 실 PG).
 *
 * <ul>
 *   <li>(a) 공지 푸시 2회 → 인앱(notifications) 계정당 1건(dedup_key 멱등).</li>
 *   <li>(b) ★ 캠페인 2회 → 인앱 계정당 2건(매번 새 UUID라 비멱등) — 공지 vs 캠페인 대비.</li>
 *   <li>(c) ★ 토큰 없는 계정 → 푸시 outbox 0건 BUT 인앱 1건(전원 도달). 인앱이 토큰 조건부였으면 0이 되어 깨짐.</li>
 * </ul>
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
class AdminInAppPushIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_inapp_it")
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
    private UUID userWithToken;
    private UUID userNoToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE notification_outbox, notifications, device_tokens, notice,"
                        + " admin_audit_log, accounts RESTART IDENTITY CASCADE");
        actorId = account("admin@test.com"); // 관리자(토큰 없음)
        userWithToken = account("u1@test.com");
        jdbcTemplate.update(
                "INSERT INTO device_tokens (account_id, token, platform) VALUES (?, ?, 'ANDROID')",
                userWithToken, "tok-" + userWithToken);
        userNoToken = account("u2@test.com"); // ★ 토큰 없음
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, status, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', 'EMAIL')",
                id, email);
        return id;
    }

    private long inApp(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE account_id = ?", Long.class, accountId);
    }

    private long outbox(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE account_id = ?", Long.class, accountId);
    }

    private Long createNotice() {
        Notice n =
                noticeRepository.save(
                        Notice.builder().title("점검").content("점검 안내").published(true)
                                .authorAccountId(actorId).build());
        return n.getId();
    }

    @Test
    @DisplayName("(a) 공지 푸시 2회 → 인앱 계정당 1건(dedup_key 멱등)")
    void noticeInApp_idempotent() {
        Long noticeId = createNotice();
        adminPushService.sendNoticePush(actorId, noticeId);
        adminPushService.sendNoticePush(actorId, noticeId); // 재발송

        assertThat(inApp(userWithToken)).as("공지 재발송 → 인앱 1건(멱등)").isEqualTo(1);
        assertThat(inApp(userNoToken)).as("토큰 없어도 인앱 1건(멱등)").isEqualTo(1);
    }

    @Test
    @DisplayName("★ (b) 캠페인 2회 → 인앱 계정당 2건(비멱등, 매번 새 UUID)")
    void campaignInApp_nonIdempotent() {
        adminPushService.sendCampaignPush(actorId, "이벤트", "할인");
        adminPushService.sendCampaignPush(actorId, "이벤트", "할인");

        assertThat(inApp(userWithToken)).as("캠페인 2회 → 인앱 2건").isEqualTo(2);
        assertThat(inApp(userNoToken)).as("토큰 없어도 캠페인 2건").isEqualTo(2);
    }

    @Test
    @DisplayName("★ (c) 토큰 없는 계정 → 푸시 outbox 0건 BUT 인앱 1건(전원 도달)")
    void tokenless_inAppReaches_pushSkips() {
        Long noticeId = createNotice();
        adminPushService.sendNoticePush(actorId, noticeId);

        // 토큰 보유자: 푸시 + 인앱
        assertThat(outbox(userWithToken)).as("토큰 보유 → 푸시 1").isEqualTo(1);
        assertThat(inApp(userWithToken)).as("토큰 보유 → 인앱 1").isEqualTo(1);

        // ★ 토큰 없음: 푸시 0(토큰 없어 skip) BUT 인앱 1(전원 도달)
        assertThat(outbox(userNoToken)).as("토큰 없음 → 푸시 0").isZero();
        assertThat(inApp(userNoToken)).as("토큰 없어도 인앱 1(FR-042 전원 도달)").isEqualTo(1);
    }
}
