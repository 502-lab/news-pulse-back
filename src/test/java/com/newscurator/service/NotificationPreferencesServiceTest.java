package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Account;
import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.NotificationPreferences;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.DevicePlatform;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.NotificationSettingsRequest;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.DeviceTokenRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.repository.NotificationRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
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
class NotificationPreferencesServiceTest {

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

    @Autowired NotificationPreferencesService preferencesService;
    @Autowired NotificationSendService notificationSendService;
    @Autowired AccountRepository accountRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired NotificationRepository notificationRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(Account.builder()
                .email("preftest-" + UUID.randomUUID() + "@example.com")
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
        notificationRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        accountRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────
    // (1) getOrDefault: DB row 없음 → 기본값 전부 true 반환
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrDefault: DB에 저장된 설정 없으면 전부 true 기본값 반환 (lazy init, row 없음)")
    void getOrDefault_noStoredPrefs_returnsAllTrue() {
        NotificationPreferences prefs = preferencesService.getOrDefault(accountId);

        assertThat(prefs.isPushEnabled()).isTrue();
        assertThat(prefs.isEmailEnabled()).isTrue();
        assertThat(prefs.isRisingEnabled()).isTrue();
        assertThat(prefs.isBiasEnabled()).isTrue();
    }

    // ─────────────────────────────────────────────────────────
    // (2) update → getOrDefault: 저장된 값이 정확히 반환됨
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("update 후 getOrDefault: 저장한 값이 그대로 조회됨")
    void update_thenGet_returnsUpdatedValues() {
        preferencesService.update(accountId, new NotificationSettingsRequest(false, true, false, true));

        NotificationPreferences prefs = preferencesService.getOrDefault(accountId);

        assertThat(prefs.isPushEnabled()).isFalse();
        assertThat(prefs.isEmailEnabled()).isTrue();
        assertThat(prefs.isRisingEnabled()).isFalse();
        assertThat(prefs.isBiasEnabled()).isTrue();
    }

    // ─────────────────────────────────────────────────────────
    // (3) pushEnabled=false → 푸시 outbox 없음, notifications row 존재 (FR-015)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("pushEnabled=false: enqueueBreaking 후 notification_outbox PUSH 0건, notifications 1건 (FR-015)")
    void pushEnabled_false_noPushOutbox_butNotificationRowExists() {
        // device token 등록 (push 가능 상태)
        deviceTokenRepository.save(DeviceToken.builder()
                .accountId(accountId)
                .token("device-token-" + UUID.randomUUID())
                .platform(DevicePlatform.ANDROID)
                .build());

        // push 비활성화
        preferencesService.update(accountId, new NotificationSettingsRequest(false, true, true, true));

        // 속보 알림 enqueue
        notificationSendService.enqueueBreaking(accountId, 12345L);

        // outbox PUSH row 없음
        List<?> pushOutboxRows = outboxRepository.findAll().stream()
                .filter(o -> o.getChannel().name().equals("PUSH")
                        && o.getAccountId().equals(accountId))
                .toList();
        assertThat(pushOutboxRows).isEmpty();

        // notifications (인앱) row 정상 생성 — FR-015: 인앱은 채널 설정과 무관
        assertThat(notificationRepository.findAll().stream()
                        .anyMatch(n -> n.getAccountId().equals(accountId)))
                .isTrue();
    }
}
