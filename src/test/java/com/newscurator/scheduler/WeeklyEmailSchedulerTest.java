package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Account;
import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.EmailSubscriptionType;
import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.EmailSubscriptionRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
            "cloud.aws.s3.bucket=test-bucket",
            "cloud.aws.cloudfront.domain=http://localhost",
            "cloud.aws.region=us-east-1"
        })
class WeeklyEmailSchedulerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WeeklyEmailScheduler weeklyEmailScheduler;
    @Autowired AccountRepository accountRepository;
    @Autowired EmailSubscriptionRepository emailSubscriptionRepository;
    @Autowired NotificationOutboxRepository outboxRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(Account.builder()
                .email("weeklyemail-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = account.getId();

        emailSubscriptionRepository.save(EmailSubscription.create(accountId, EmailSubscriptionType.WEEKLY_EMAIL));
    }

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
        emailSubscriptionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────
    // (1) active 구독자 존재 → 스케줄러 실행 → EMAIL outbox 1건
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("active WEEKLY_EMAIL 구독자 1명 → sendWeeklyEmailForWeek → outbox EMAIL 1건")
    void sendWeeklyEmailForWeek_activeSubscriber_createsOneOutbox() {
        weeklyEmailScheduler.sendWeeklyEmailForWeek("2026-W24");

        long emailOutboxCount = outboxRepository.findAll().stream()
                .filter(o -> o.getChannel() == NotificationChannel.EMAIL
                        && o.getAccountId().equals(accountId))
                .count();
        assertThat(emailOutboxCount).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────
    // (2) 같은 yearWeek 재실행 → 멱등 (outbox 추가 없음)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 yearWeek 2회 실행 → outbox 1건 (중복 차단 멱등)")
    void sendWeeklyEmailForWeek_sameWeekTwice_idempotent() {
        weeklyEmailScheduler.sendWeeklyEmailForWeek("2026-W24");
        weeklyEmailScheduler.sendWeeklyEmailForWeek("2026-W24");

        long emailOutboxCount = outboxRepository.findAll().stream()
                .filter(o -> o.getChannel() == NotificationChannel.EMAIL
                        && o.getAccountId().equals(accountId))
                .count();
        assertThat(emailOutboxCount).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────
    // (3) 다음 주차 실행 → 신규 outbox 1건 추가
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("다음 주차(W25)로 실행 → 신규 outbox 1건 추가")
    void sendWeeklyEmailForWeek_nextWeek_createsNewOutbox() {
        weeklyEmailScheduler.sendWeeklyEmailForWeek("2026-W24");
        weeklyEmailScheduler.sendWeeklyEmailForWeek("2026-W25");

        long emailOutboxCount = outboxRepository.findAll().stream()
                .filter(o -> o.getChannel() == NotificationChannel.EMAIL
                        && o.getAccountId().equals(accountId))
                .count();
        assertThat(emailOutboxCount).isEqualTo(2);
    }
}
