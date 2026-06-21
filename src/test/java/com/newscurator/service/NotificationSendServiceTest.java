package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.Account;
import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.Notification;
import com.newscurator.domain.NotificationPreferences;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.DeviceTokenRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationSendServiceTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private NotificationPreferencesService preferencesService;
    @Mock private PlatformTransactionManager transactionManager;

    private NotificationSendService notificationSendService;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // TransactionTemplate이 callback을 실행할 수 있도록 mock status 제공
        TransactionStatus mockStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(mockStatus);

        notificationSendService = new NotificationSendService(
                notificationService,
                outboxRepository,
                deviceTokenRepository,
                accountRepository,
                preferencesService,
                transactionManager,
                new ObjectMapper());

        // 기본: 푸시 활성화, 토큰 없음
        NotificationPreferences defaultPrefs = new NotificationPreferences(ACCOUNT_ID);
        when(preferencesService.getOrDefault(any())).thenReturn(defaultPrefs);
        when(deviceTokenRepository.findByAccountId(any())).thenReturn(List.of());
    }

    private Notification stubNotification(NotificationType type) {
        Notification n = org.mockito.Mockito.mock(Notification.class);
        when(n.getId()).thenReturn(100L);
        when(n.getTitle()).thenReturn("test title");
        when(n.getBody()).thenReturn("test body");
        return n;
    }

    // ─────────────────────────────────────────────────────────
    // (1) PUSH 멱등성: 같은 notificationId 두 번 → 두 번째 DataIntegrityViolation 조용히 무시
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("enqueuePush: 동일 key 두 번째 호출은 DataIntegrityViolationException을 조용히 무시한다")
    void enqueuePush_duplicateKey_silentlyIgnored() {
        Long notificationId = 42L;
        String token = "fcm-token-abc";

        // 두 번째 저장 시 UNIQUE 위반 시뮬레이션
        when(outboxRepository.save(any()))
                .thenReturn(null)
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatCode(() -> {
            notificationSendService.enqueuePush(ACCOUNT_ID, notificationId, token, "title", "body");
            notificationSendService.enqueuePush(ACCOUNT_ID, notificationId, token, "title", "body");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enqueuePush: 다른 notificationId는 각각 정상 저장")
    void enqueuePush_differentKeys_bothSaved() {
        when(outboxRepository.save(any())).thenReturn(null);

        assertThatCode(() -> {
            notificationSendService.enqueuePush(ACCOUNT_ID, 1L, "token-a", "t", "b");
            notificationSendService.enqueuePush(ACCOUNT_ID, 2L, "token-b", "t", "b");
        }).doesNotThrowAnyException();

        verify(outboxRepository, times(2)).save(any());
    }

    // ─────────────────────────────────────────────────────────
    // (2) 주간 이메일 멱등성: 동일 yearWeek 두 번 → 두 번째 무시
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("enqueueWeeklyEmail: 동일 yearWeek 두 번째 호출은 DataIntegrityViolationException을 조용히 무시")
    void enqueueWeeklyEmail_sameWeek_secondCallSilentlyIgnored() {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getEmail()).thenReturn("test@example.com");
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        when(outboxRepository.save(any()))
                .thenReturn(null)
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatCode(() -> {
            notificationSendService.enqueueWeeklyEmail(ACCOUNT_ID, "2026-W24");
            notificationSendService.enqueueWeeklyEmail(ACCOUNT_ID, "2026-W24");
        }).doesNotThrowAnyException();

        verify(outboxRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("enqueueWeeklyEmail: 다른 yearWeek는 각각 저장")
    void enqueueWeeklyEmail_differentWeeks_bothSaved() {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getEmail()).thenReturn("test@example.com");
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(outboxRepository.save(any())).thenReturn(null);

        assertThatCode(() -> {
            notificationSendService.enqueueWeeklyEmail(ACCOUNT_ID, "2026-W24");
            notificationSendService.enqueueWeeklyEmail(ACCOUNT_ID, "2026-W25");
        }).doesNotThrowAnyException();

        verify(outboxRepository, times(2)).save(any());
    }

    // ─────────────────────────────────────────────────────────
    // (3) enqueueBriefing: 인앱 알림 생성 + 토큰 있으면 outbox enqueue
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("enqueueBriefing: createNotification 호출, 토큰 있으면 enqueuePush outbox 1건")
    void enqueueBriefing_withDeviceToken_enqueuesOutbox() {
        Notification n = stubNotification(NotificationType.BRIEFING);
        when(notificationService.createNotification(eq(ACCOUNT_ID), eq(NotificationType.BRIEFING),
                any(), any(), any())).thenReturn(n);

        DeviceToken dt = org.mockito.Mockito.mock(DeviceToken.class);
        when(dt.getToken()).thenReturn("fcm-token");
        when(deviceTokenRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(dt));
        when(outboxRepository.save(any())).thenReturn(null);

        assertThatCode(() -> notificationSendService.enqueueBriefing(ACCOUNT_ID))
                .doesNotThrowAnyException();

        verify(notificationService).createNotification(eq(ACCOUNT_ID), eq(NotificationType.BRIEFING),
                any(), any(), any());
        verify(outboxRepository, times(1)).save(any());
    }
}
