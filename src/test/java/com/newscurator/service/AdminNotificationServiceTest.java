package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Account;
import com.newscurator.domain.TopicSubscription;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.AdminTargetType;
import com.newscurator.domain.enums.NotificationTopic;
import com.newscurator.dto.request.AdminNotificationRequest;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.TopicSubscriptionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TopicSubscriptionRepository topicSubscriptionRepository;
    @Mock private NotificationSendService notificationSendService;

    private AdminNotificationService adminNotificationService;

    @BeforeEach
    void setUp() {
        adminNotificationService = new AdminNotificationService(
                accountRepository, topicSubscriptionRepository, notificationSendService);
    }

    private Account stubAccount(UUID id) {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    private TopicSubscription stubSubscription(UUID accountId, NotificationTopic topic) {
        TopicSubscription sub = new TopicSubscription(accountId, topic);
        return sub;
    }

    // ─────────────────────────────────────────────────────────
    // (1) targetType=ALL → active 계정 전체에 enqueueSystem 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("targetType=ALL → 전체 active account 수만큼 enqueueSystem 호출")
    void sendNotification_targetAll_callsEnqueueForAllActiveAccounts() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Account a1 = stubAccount(id1);
        Account a2 = stubAccount(id2);
        Account a3 = stubAccount(id3);
        when(accountRepository.findByStatus(AccountStatus.ACTIVE))
                .thenReturn(List.of(a1, a2, a3));

        AdminNotificationRequest req = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.ALL, null, null);
        adminNotificationService.sendNotification(req);

        verify(notificationSendService, times(3)).enqueueSystem(any(), eq("공지"), eq("내용"));
    }

    // ─────────────────────────────────────────────────────────
    // (2) targetType=ACCOUNT_IDS → 지정된 accountId에만 enqueueSystem 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("targetType=ACCOUNT_IDS → 지정된 UUID에만 enqueueSystem 호출")
    void sendNotification_targetAccountIds_callsEnqueueOnlyForSpecifiedIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        AdminNotificationRequest req = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.ACCOUNT_IDS, List.of(id1, id2), null);
        adminNotificationService.sendNotification(req);

        verify(notificationSendService).enqueueSystem(eq(id1), eq("공지"), eq("내용"));
        verify(notificationSendService).enqueueSystem(eq(id2), eq("공지"), eq("내용"));
        verify(notificationSendService, times(2)).enqueueSystem(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────
    // (3) targetType=TOPIC_SUBSCRIBERS + topic=BRIEFING → 구독자에게만 enqueueSystem 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("targetType=TOPIC_SUBSCRIBERS + topic=BRIEFING → BRIEFING 구독자에게만 enqueueSystem 호출")
    void sendNotification_targetTopicSubscribers_callsEnqueueForBriefingSubscribers() {
        UUID sub1 = UUID.randomUUID();
        UUID sub2 = UUID.randomUUID();
        when(topicSubscriptionRepository.findByIdTopic(NotificationTopic.BRIEFING))
                .thenReturn(List.of(
                        stubSubscription(sub1, NotificationTopic.BRIEFING),
                        stubSubscription(sub2, NotificationTopic.BRIEFING)));

        AdminNotificationRequest req = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.TOPIC_SUBSCRIBERS, null, NotificationTopic.BRIEFING);
        adminNotificationService.sendNotification(req);

        verify(notificationSendService).enqueueSystem(eq(sub1), eq("공지"), eq("내용"));
        verify(notificationSendService).enqueueSystem(eq(sub2), eq("공지"), eq("내용"));
        verify(notificationSendService, times(2)).enqueueSystem(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────
    // (L1) targetType=ACCOUNT_IDS + accountIds=null → 예외 없음, enqueueSystem 0회
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("targetType=ACCOUNT_IDS + accountIds=null → 예외 전파 없음, enqueueSystem 0회 (방어 처리)")
    void sendNotification_targetAccountIds_nullList_noExceptionAndNoEnqueue() {
        AdminNotificationRequest req = new AdminNotificationRequest(
                "공지", "내용", AdminTargetType.ACCOUNT_IDS, null, null);

        assertThatCode(() -> adminNotificationService.sendNotification(req))
                .doesNotThrowAnyException();

        verify(notificationSendService, never()).enqueueSystem(any(), any(), any());
    }
}
