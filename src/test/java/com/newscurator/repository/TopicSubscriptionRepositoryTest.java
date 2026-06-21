package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.NotificationTopic;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.response.TopicSubscriptionsResponse;
import com.newscurator.service.TopicSubscriptionService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
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
class TopicSubscriptionRepositoryTest {

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

    @Autowired TopicSubscriptionRepository topicSubscriptionRepository;
    @Autowired TopicSubscriptionService topicSubscriptionService;
    @Autowired AccountRepository accountRepository;

    private UUID accountId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccount = accountRepository.save(Account.builder()
                .email("topictest-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = testAccount.getId();
    }

    // ─────────────────────────────────────────────────────────
    // (1) replaceAll: 기존 구독 전부 삭제 후 신규 저장
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) replaceAll: 기존 BREAKING 구독 → BRIEFING으로 교체 → DB에 BRIEFING만 존재")
    void replaceAll_existingSubscriptions_replacedWithNewSet() {
        topicSubscriptionService.replaceAll(accountId, List.of(NotificationTopic.BREAKING));
        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).hasSize(1);

        topicSubscriptionService.replaceAll(accountId, List.of(NotificationTopic.BRIEFING, NotificationTopic.TTS_READY));

        List<NotificationTopic> topics = topicSubscriptionRepository.findByIdAccountId(accountId)
                .stream().map(s -> s.getId().getTopic()).toList();

        assertThat(topics).hasSize(2)
                .containsExactlyInAnyOrder(NotificationTopic.BRIEFING, NotificationTopic.TTS_READY)
                .doesNotContain(NotificationTopic.BREAKING);
    }

    // ─────────────────────────────────────────────────────────
    // (2) 중복 구독 무시 — replaceAll 내 distinct 처리
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) 중복 토픽 포함 replaceAll → distinct 처리로 DB에 중복 없음")
    void replaceAll_withDuplicateTopics_storesDeduplicated() {
        TopicSubscriptionsResponse response = topicSubscriptionService.replaceAll(
                accountId,
                List.of(NotificationTopic.BREAKING, NotificationTopic.BREAKING, NotificationTopic.BRIEFING));

        // 응답에도 중복 없음
        assertThat(response.topics()).doesNotHaveDuplicates();

        // DB에도 row 2개만
        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────
    // (3) 구독 해제 — 빈 목록으로 교체
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) 빈 목록으로 replaceAll → 전체 구독 해제, DB row 0")
    void replaceAll_emptyList_deletesAllSubscriptions() {
        topicSubscriptionService.replaceAll(accountId,
                List.of(NotificationTopic.BREAKING, NotificationTopic.BRIEFING));
        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).hasSize(2);

        topicSubscriptionService.replaceAll(accountId, List.of());

        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────
    // (4) cascade delete: account 삭제 시 topic_subscriptions 함께 삭제
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) account 삭제 시 topic_subscriptions CASCADE DELETE")
    void deleteAccount_cascadeDeletesTopicSubscriptions() {
        topicSubscriptionService.replaceAll(accountId, List.of(NotificationTopic.BREAKING));
        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).hasSize(1);

        accountRepository.delete(testAccount);
        accountRepository.flush();

        assertThat(topicSubscriptionRepository.findByIdAccountId(accountId)).isEmpty();
    }
}
