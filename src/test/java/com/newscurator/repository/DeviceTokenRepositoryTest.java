package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Account;
import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.DevicePlatform;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.service.DeviceTokenService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
class DeviceTokenRepositoryTest {

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

    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired DeviceTokenService deviceTokenService;
    @Autowired AccountRepository accountRepository;

    private Account testAccount;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        testAccount = accountRepository.save(Account.builder()
                .email("tokentest-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = testAccount.getId();
    }

    // ─────────────────────────────────────────────────────────
    // (1) upsert: positive — 동일 token 재등록 → row 추가 없이 갱신
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1-positive) 동일 token upsert 2회 → DB row 1개만 존재, account_id 갱신됨")
    void upsert_sameToken_doesNotInsertNewRow() {
        String token = "fcm-token-" + UUID.randomUUID();

        deviceTokenRepository.upsert(accountId.toString(), token, DevicePlatform.IOS.name());
        deviceTokenRepository.upsert(accountId.toString(), token, DevicePlatform.ANDROID.name());
        deviceTokenRepository.flush();

        // row 1개만 존재 (중복 INSERT 아닌 UPDATE)
        List<DeviceToken> tokens = deviceTokenRepository.findByAccountId(accountId);
        assertThat(tokens).hasSize(1);

        // platform이 두 번째 값(ANDROID)으로 갱신됨
        Optional<DeviceToken> found = deviceTokenRepository.findByToken(token);
        assertThat(found).isPresent();
        assertThat(found.get().getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(found.get().getAccountId()).isEqualTo(accountId);
    }

    // ─────────────────────────────────────────────────────────
    // (1) upsert: negative — 다른 token이면 별도 row 생성
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1-negative) 서로 다른 token 2개 등록 → row 2개 각각 존재")
    void upsert_differentTokens_insertsTwoSeparateRows() {
        String tokenA = "fcm-token-A-" + UUID.randomUUID();
        String tokenB = "fcm-token-B-" + UUID.randomUUID();

        deviceTokenRepository.upsert(accountId.toString(), tokenA, DevicePlatform.IOS.name());
        deviceTokenRepository.upsert(accountId.toString(), tokenB, DevicePlatform.ANDROID.name());
        deviceTokenRepository.flush();

        List<DeviceToken> tokens = deviceTokenRepository.findByAccountId(accountId);
        assertThat(tokens).hasSize(2);
        assertThat(tokens).extracting(DeviceToken::getToken)
                .containsExactlyInAnyOrder(tokenA, tokenB);
    }

    // ─────────────────────────────────────────────────────────
    // (2) max-5 eviction: 6번째 등록 시 가장 오래된 토큰 삭제
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) 6번째 토큰 등록 시 가장 오래된(created_at) 토큰 1개 삭제 — 정확히 5개 유지")
    void register_sixthToken_evictsOldest_exactlyFiveRemain() throws InterruptedException {
        // 5개 먼저 등록 (created_at 구분을 위해 1ms 간격)
        String oldestToken = "oldest-" + UUID.randomUUID();
        deviceTokenService.register(accountId, oldestToken, DevicePlatform.IOS);
        Thread.sleep(2);

        for (int i = 1; i <= 4; i++) {
            Thread.sleep(2);
            deviceTokenService.register(accountId, "token-" + i + "-" + UUID.randomUUID(), DevicePlatform.IOS);
        }

        long countBefore = deviceTokenRepository.countByAccountId(accountId);
        assertThat(countBefore).isEqualTo(5);

        // 6번째 등록
        Thread.sleep(2);
        deviceTokenService.register(accountId, "sixth-" + UUID.randomUUID(), DevicePlatform.IOS);

        // 정확히 5개 유지
        long countAfter = deviceTokenRepository.countByAccountId(accountId);
        assertThat(countAfter).isEqualTo(5);

        // 가장 오래된 토큰(oldestToken)이 삭제됨
        Optional<DeviceToken> evicted = deviceTokenRepository.findByToken(oldestToken);
        assertThat(evicted).isEmpty();
    }

    // ─────────────────────────────────────────────────────────
    // (3) cascade delete: account 삭제 시 device_tokens 함께 삭제
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) account 삭제 시 device_tokens CASCADE DELETE")
    void deleteAccount_cascadeDeletesDeviceTokens() {
        deviceTokenService.register(accountId, "cascade-token-" + UUID.randomUUID(), DevicePlatform.IOS);
        deviceTokenRepository.flush();

        assertThat(deviceTokenRepository.countByAccountId(accountId)).isEqualTo(1);

        accountRepository.delete(testAccount);
        accountRepository.flush();

        assertThat(deviceTokenRepository.countByAccountId(accountId)).isZero();
    }
}
