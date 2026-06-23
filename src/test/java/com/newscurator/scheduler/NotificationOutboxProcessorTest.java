package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.client.notification.EmailPort;
import com.newscurator.client.notification.PushNotificationPort;
import com.newscurator.domain.Account;
import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.Notification;
import com.newscurator.domain.NotificationOutbox;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.DevicePlatform;
import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.domain.enums.NotificationOutboxStatus;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.exception.FcmUnregisteredException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.DeviceTokenRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.repository.NotificationRepository;
import com.newscurator.service.DeviceTokenService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
class NotificationOutboxProcessorTest {

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

    @Autowired NotificationOutboxClaimer claimer;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired DeviceTokenService deviceTokenService;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.newscurator.service.admin.SchedulerControlService schedulerControl;

    private UUID accountId;
    private PushNotificationPort mockPushPort;
    private EmailPort mockEmailPort;
    private NotificationOutboxProcessor processor;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(Account.builder()
                .email("processortest-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = account.getId();

        mockPushPort = mock(PushNotificationPort.class);
        mockEmailPort = mock(EmailPort.class);
        processor = new NotificationOutboxProcessor(
                claimer, mockPushPort, mockEmailPort, deviceTokenService, objectMapper, 50, schedulerControl);
    }

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
        notificationRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private NotificationOutbox savePushOutbox(String token) {
        NotificationOutbox saved = outboxRepository.save(NotificationOutbox.builder()
                .accountId(accountId)
                .channel(NotificationChannel.PUSH)
                .payload("{\"token\":\"" + token + "\",\"title\":\"t\",\"body\":\"b\"}")
                .idempotencyKey("PUSH:" + accountId + ":" + UUID.randomUUID())
                .build());
        resetNextRetryAt(saved.getId());
        return saved;
    }

    private NotificationOutbox saveEmailOutbox() {
        NotificationOutbox saved = outboxRepository.save(NotificationOutbox.builder()
                .accountId(accountId)
                .channel(NotificationChannel.EMAIL)
                .payload("{\"to\":\"test@example.com\",\"subject\":\"s\",\"htmlBody\":\"<p>h</p>\"}")
                .idempotencyKey("EMAIL:WEEKLY:" + accountId + ":" + UUID.randomUUID())
                .build());
        resetNextRetryAt(saved.getId());
        return saved;
    }

    /**
     * next_retry_at을 과거로 설정해 즉시 클레임 가능하게 한다.
     * REQUIRES_NEW 트랜잭션으로 실행해 기존 TX 상태 및 HikariCP connection 상태와 무관하게
     * 항상 독립 커밋을 보장한다.
     */
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
    // (1) PENDING → PROCESSING → SENT 정상 flow (EMAIL 채널)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMAIL outbox PENDING → processor.process() → status=SENT")
    void process_emailOutbox_pendingBecomeSent() {
        saveEmailOutbox();

        processor.process();

        verify(mockEmailPort, times(1)).send(any(), any(), any());
        List<NotificationOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
    }

    // ─────────────────────────────────────────────────────────
    // (2) FcmUnregisteredException → 토큰 삭제 + outbox FAILED
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("FcmUnregisteredException 시 device_token 행 삭제, outbox status=FAILED")
    void process_fcmUnregistered_deletesTokenAndMarksFailed() {
        String token = "stale-token-" + UUID.randomUUID();
        deviceTokenRepository.save(DeviceToken.builder()
                .accountId(accountId)
                .token(token)
                .platform(DevicePlatform.ANDROID)
                .build());

        savePushOutbox(token);

        doThrow(new FcmUnregisteredException(token))
                .when(mockPushPort).send(anyString(), anyString(), anyString());

        processor.process();

        assertThat(deviceTokenRepository.findByToken(token)).isEmpty();

        List<NotificationOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
    }

    // ─────────────────────────────────────────────────────────
    // (3) 3회 실패 → status=FAILED (attempt_count >= 3)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUSH 3회 실패 → attemptCount=3, status=FAILED")
    void process_threeFails_statusFailed() {
        String token = "token-" + UUID.randomUUID();
        NotificationOutbox outbox = savePushOutbox(token);

        doThrow(new RuntimeException("transient error"))
                .when(mockPushPort).send(any(), any(), any());

        // attempt 1
        processor.process();
        resetNextRetryAt(outbox.getId());

        // attempt 2
        processor.process();
        resetNextRetryAt(outbox.getId());

        // attempt 3 → FAILED
        processor.process();

        NotificationOutbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(result.getAttemptCount()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────
    // (4) 2 인스턴스 동시 처리 → pushPort.send() 1회만 호출(SKIP LOCKED)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 인스턴스 동시 process() → send() 1회만 호출")
    void process_twoInstances_sendCalledOnce() throws Exception {
        savePushOutbox("concurrent-token-" + UUID.randomUUID());

        NotificationOutboxProcessor processor2 = new NotificationOutboxProcessor(
                claimer, mockPushPort, mockEmailPort, deviceTokenService, objectMapper, 50, schedulerControl);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Exception> errors = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (NotificationOutboxProcessor p : List.of(processor, processor2)) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    p.process();
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
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        verify(mockPushPort, times(1)).send(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────
    // (5) FR-012: Firebase 미초기화 시 인앱 알림 row 영향 없음, outbox만 FAILED
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("FR-012: pushPort 예외(Firebase 미초기화 시뮬) → notifications row 정상 존재, outbox 3회 후 FAILED")
    void process_firebaseUninitialized_inappNotificationUnaffected() {
        Notification inapp = notificationRepository.save(Notification.builder()
                .accountId(accountId)
                .type(NotificationType.BRIEFING)
                .title("브리핑")
                .body("내용")
                .referenceId(null)
                .build());

        NotificationOutbox outbox = outboxRepository.save(NotificationOutbox.builder()
                .accountId(accountId)
                .notificationId(inapp.getId())
                .channel(NotificationChannel.PUSH)
                .payload("{\"token\":\"t\",\"title\":\"브리핑\",\"body\":\"내용\"}")
                .idempotencyKey("PUSH:" + accountId + ":" + inapp.getId())
                .build());
        resetNextRetryAt(outbox.getId());

        doThrow(new IllegalStateException("Firebase not initialized"))
                .when(mockPushPort).send(any(), any(), any());

        // 3회 처리 → FAILED
        processor.process();
        resetNextRetryAt(outbox.getId());
        processor.process();
        resetNextRetryAt(outbox.getId());
        processor.process();

        // 인앱 알림 row 정상 존재 (inapp 생성은 processor와 무관)
        assertThat(notificationRepository.findById(inapp.getId())).isPresent();

        // outbox FAILED
        NotificationOutbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
    }
}
