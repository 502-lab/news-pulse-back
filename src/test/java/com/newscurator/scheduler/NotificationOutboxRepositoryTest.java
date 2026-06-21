package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.Account;
import com.newscurator.domain.NotificationOutbox;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.domain.enums.NotificationOutboxStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
            "cloud.aws.region=us-east-1",
            "spring.datasource.hikari.maximum-pool-size=10"
        })
class NotificationOutboxRepositoryTest {

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

    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired NotificationOutboxClaimer claimer;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(Account.builder()
                .email("outboxtest-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = account.getId();
    }

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private NotificationOutbox saveOutbox(String idempotencyKey) {
        return outboxRepository.save(NotificationOutbox.builder()
                .accountId(accountId)
                .channel(NotificationChannel.PUSH)
                .payload("{\"token\":\"fcm-token\",\"title\":\"t\",\"body\":\"b\"}")
                .idempotencyKey(idempotencyKey)
                .build());
    }

    // ─────────────────────────────────────────────────────────
    // (1) PENDING 클레임: 첫 호출 → PROCESSING 마킹, 두 번째 → 빈 결과
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING 3건 → claimBatch(10) → 전부 PROCESSING, 재클레임 → 빈 리스트")
    void claimBatch_pending3_allBecomesProcessing_secondCallReturnsEmpty() {
        saveOutbox("key-1");
        saveOutbox("key-2");
        saveOutbox("key-3");

        List<NotificationOutbox> first = claimer.claimBatch(10);

        assertThat(first).hasSize(3);
        assertThat(first).extracting(NotificationOutbox::getStatus)
                .containsOnly(NotificationOutboxStatus.PROCESSING);

        List<NotificationOutbox> dbRows = outboxRepository.findAll();
        assertThat(dbRows).extracting(NotificationOutbox::getStatus)
                .containsOnly(NotificationOutboxStatus.PROCESSING);

        List<NotificationOutbox> second = claimer.claimBatch(10);
        assertThat(second).isEmpty();
    }

    // ─────────────────────────────────────────────────────────
    // (2) FOR UPDATE SKIP LOCKED 동시성: 2 스레드 경쟁 → 1건만 클레임
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 스레드 동시 claimBatch(1) → 합산 1건(SKIP LOCKED 보장)")
    void claimBatch_concurrent2Threads_onlyOneClaimsTheRow() throws Exception {
        NotificationOutbox outbox = saveOutbox("concurrent-key-" + UUID.randomUUID());
        resetNextRetryAt(outbox.getId());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger totalClaimed = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    List<NotificationOutbox> claimed = claimer.claimBatch(1);
                    totalClaimed.addAndGet(claimed.size());
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(totalClaimed.get()).isEqualTo(1);
    }

    // next_retry_at을 PostgreSQL NOW() 기준 과거로 세팅 — Java/DB 시계 차이로 인한
    // "적격 row 없음 → 0건 클레임" vector 제거
    private void resetNextRetryAt(Long outboxId) {
        TransactionTemplate tmpl = new TransactionTemplate(transactionManager);
        tmpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tmpl.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE notification_outbox SET next_retry_at = NOW() - INTERVAL '5 seconds' WHERE id = ?",
                    outboxId);
            return null;
        });
    }

    // ─────────────────────────────────────────────────────────
    // (3) idempotency_key UNIQUE 위반 → DataIntegrityViolationException
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 idempotency_key 2회 INSERT → DataIntegrityViolationException")
    void save_duplicateIdempotencyKey_throwsDataIntegrityViolation() {
        String key = "unique-key-" + UUID.randomUUID();
        saveOutbox(key);

        assertThatThrownBy(() -> {
            outboxRepository.saveAndFlush(NotificationOutbox.builder()
                    .accountId(accountId)
                    .channel(NotificationChannel.PUSH)
                    .payload("{\"token\":\"t\",\"title\":\"t\",\"body\":\"b\"}")
                    .idempotencyKey(key)
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
