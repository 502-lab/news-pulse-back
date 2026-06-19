package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.newscurator.domain.Account;
import com.newscurator.domain.Notification;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.DeviceTokenRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.repository.NotificationRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
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
class NotificationSendServiceIntegrationTest {

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

    @Autowired NotificationSendService notificationSendService;
    @Autowired AccountRepository accountRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
        notificationRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        accountRepository.deleteAll();
    }

    /**
     * REQUIRES_NEW 격리 증명 시나리오:
     * - 외부 TX 에서 작업 W(Account 저장) 수행
     * - 같은 TX 스코프 안에서 동일 idempotency_key 로 enqueuePush 2회 호출
     * - 두 번째 호출: REQUIRES_NEW TX 내부에서 UNIQUE 위반 발생 → 내부 TX 만 롤백, 외부 TX 오염 없음
     * - 결과: 예외 전파 없음 / W 커밋됨 / outbox row 정확히 1개
     */
    @Test
    @DisplayName("외부 TX 안에서 동일 key enqueue 2회 → 예외 전파 없음, 바깥 작업 W 커밋, outbox row 1개")
    void enqueueWithinOuterTransaction_requiresNewIsolation() {
        // Given: accountId와 notification을 외부 TX 이전에 커밋
        Account owner = accountRepository.save(
                Account.builder()
                        .email("owner-" + UUID.randomUUID() + "@example.com")
                        .passwordHash("hash")
                        .role(AccountRole.USER)
                        .status(AccountStatus.ACTIVE)
                        .signupType(SignupType.EMAIL)
                        .emailVerified(true)
                        .build());
        UUID accountId = owner.getId();

        Notification notification = notificationRepository.save(
                Notification.builder()
                        .accountId(accountId)
                        .type(NotificationType.SYSTEM)
                        .title("test")
                        .body("test body")
                        .referenceId(null)
                        .build());

        // 외부 TX 에서 동일 key 로 두 번 enqueue 한다
        String workEmail = "work-w-" + UUID.randomUUID() + "@example.com";
        TransactionTemplate outerTx = new TransactionTemplate(transactionManager);

        // (1) 예외 전파 없음
        assertThatCode(() ->
                outerTx.execute(status -> {
                    // Work W: 외부 TX 안에서 Account 저장
                    accountRepository.save(
                            Account.builder()
                                    .email(workEmail)
                                    .passwordHash("hash")
                                    .role(AccountRole.USER)
                                    .status(AccountStatus.ACTIVE)
                                    .signupType(SignupType.EMAIL)
                                    .emailVerified(true)
                                    .build());

                    // 동일 key 1회차: REQUIRES_NEW TX → 저장 후 커밋
                    notificationSendService.enqueuePush(
                            accountId, notification.getId(), "token-dup", "title", "body");
                    // 동일 key 2회차: REQUIRES_NEW TX → UNIQUE 위반 → 내부 TX 롤백, 예외 catch
                    notificationSendService.enqueuePush(
                            accountId, notification.getId(), "token-dup", "title", "body");

                    return null;
                }))
                .doesNotThrowAnyException();

        // (2) 바깥 작업 W 정상 커밋
        assertThat(accountRepository.findByEmailIgnoreCase(workEmail)).isPresent();

        // (3) outbox row 정확히 1개 (UNIQUE 멱등)
        assertThat(outboxRepository.findAll()).hasSize(1);
        assertThat(outboxRepository.findAll().get(0).getIdempotencyKey())
                .isEqualTo("PUSH:" + accountId + ":" + notification.getId());
    }
}
