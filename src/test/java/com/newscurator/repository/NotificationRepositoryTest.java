package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Account;
import com.newscurator.domain.Notification;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
class NotificationRepositoryTest {

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

    @Autowired NotificationRepository notificationRepository;
    @Autowired AccountRepository accountRepository;

    private UUID accountId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccount = accountRepository.save(Account.builder()
                .email("notiftest-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        accountId = testAccount.getId();
    }

    private Notification save(UUID accId, NotificationType type, boolean read) {
        Notification n = Notification.builder()
                .accountId(accId)
                .type(type)
                .title("제목-" + UUID.randomUUID())
                .body("본문")
                .referenceId(null)
                .build();
        notificationRepository.save(n);
        if (read) {
            n.markRead();
            notificationRepository.save(n);
        }
        return n;
    }

    // ─────────────────────────────────────────────────────────
    // (1) 페이지네이션 + created_at DESC 정렬
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 알림 목록 페이지네이션 — size=2, 총 3건 → page0에 2건, page1에 1건, created_at DESC 정렬 단언")
    void list_pageable_returnsCorrectPageAndOrder() throws InterruptedException {
        Notification n1 = save(accountId, NotificationType.BREAKING, false);
        Thread.sleep(2);
        Notification n2 = save(accountId, NotificationType.BRIEFING, false);
        Thread.sleep(2);
        Notification n3 = save(accountId, NotificationType.SYSTEM, false);

        Page<Notification> page0 = notificationRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, 2));
        Page<Notification> page1 = notificationRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(1, 2));

        // 페이지네이션 메타
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page1.getContent()).hasSize(1);

        // created_at DESC 정렬 — 첫 페이지 첫 번째가 가장 최신(n3)
        List<Notification> first = page0.getContent();
        assertThat(first.get(0).getId()).isEqualTo(n3.getId());
        assertThat(first.get(1).getId()).isEqualTo(n2.getId());
        assertThat(page1.getContent().get(0).getId()).isEqualTo(n1.getId());
    }

    // ─────────────────────────────────────────────────────────
    // (2) unread=true 필터
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) unread 필터 — 읽음 1건 + 미읽음 2건 저장 시 unread 조회 결과에 미읽음 2건만 포함")
    void list_unreadFilter_returnsOnlyUnread() {
        save(accountId, NotificationType.BREAKING, true);   // 읽음
        save(accountId, NotificationType.BRIEFING, false);  // 미읽음
        save(accountId, NotificationType.SYSTEM, false);    // 미읽음

        Page<Notification> all = notificationRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, 10));
        Page<Notification> unread = notificationRepository
                .findByAccountIdAndIsReadFalseOrderByCreatedAtDesc(accountId, PageRequest.of(0, 10));

        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(unread.getTotalElements()).isEqualTo(2);
        assertThat(unread.getContent()).allMatch(n -> !n.isRead());
    }

    // ─────────────────────────────────────────────────────────
    // (3) markAllReadByAccountId — 벌크 업데이트 DB 단언
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) markAllReadByAccountId — 해당 account 미읽음 전부 true, 다른 account 영향 없음")
    void markAllRead_onlyAffectsTargetAccount() {
        // 대상 account: 미읽음 2건
        save(accountId, NotificationType.BREAKING, false);
        save(accountId, NotificationType.BRIEFING, false);

        // 다른 account: 미읽음 1건
        Account other = accountRepository.save(Account.builder()
                .email("other-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
        save(other.getId(), NotificationType.SYSTEM, false);

        int updated = notificationRepository.markAllReadByAccountId(accountId);
        notificationRepository.flush();

        // 대상 account: 전부 읽음
        assertThat(updated).isEqualTo(2);
        Page<Notification> unreadAfter = notificationRepository
                .findByAccountIdAndIsReadFalseOrderByCreatedAtDesc(accountId, PageRequest.of(0, 10));
        assertThat(unreadAfter.getTotalElements()).isZero();

        // 다른 account: 영향 없음
        Page<Notification> otherUnread = notificationRepository
                .findByAccountIdAndIsReadFalseOrderByCreatedAtDesc(other.getId(), PageRequest.of(0, 10));
        assertThat(otherUnread.getTotalElements()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────
    // (4) deleteByExpiresAtBefore — 만료 자동삭제 (positive + negative)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4-positive) expires_at 지난 알림 삭제 — 만료된 것만 제거, 유효한 것 보존")
    void deleteExpired_removesOnlyExpiredNotifications() {
        // 이미 만료된 알림: expiresAt을 과거로 강제 설정 (네이티브 쿼리)
        Notification expired = save(accountId, NotificationType.SYSTEM, false);
        notificationRepository.flush();
        // expires_at을 과거로 직접 업데이트
        notificationRepository.updateExpiresAt(expired.getId(), Instant.now().minus(1, ChronoUnit.DAYS));
        notificationRepository.flush();

        // 아직 유효한 알림 (expires_at = 생성 + 90일 → 미래)
        Notification valid = save(accountId, NotificationType.BREAKING, false);
        notificationRepository.flush();

        int deleted = notificationRepository.deleteByExpiresAtBefore(Instant.now());
        notificationRepository.flush();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(notificationRepository.findByIdAndAccountId(expired.getId(), accountId)).isEmpty();
        assertThat(notificationRepository.findByIdAndAccountId(valid.getId(), accountId)).isPresent();
    }

    @Test
    @DisplayName("(4-negative) 만료되지 않은 알림만 있으면 deleteByExpiresAtBefore → 삭제 0건")
    void deleteExpired_noExpiredNotifications_deletesNothing() {
        save(accountId, NotificationType.BREAKING, false);
        save(accountId, NotificationType.BRIEFING, false);
        notificationRepository.flush();

        int deleted = notificationRepository.deleteByExpiresAtBefore(Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(deleted).isZero();
        assertThat(notificationRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }
}
