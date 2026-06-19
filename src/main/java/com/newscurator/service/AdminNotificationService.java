package com.newscurator.service;

import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.dto.request.AdminNotificationRequest;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.TopicSubscriptionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationService.class);

    private final AccountRepository accountRepository;
    private final TopicSubscriptionRepository topicSubscriptionRepository;
    private final NotificationSendService notificationSendService;

    public AdminNotificationService(
            AccountRepository accountRepository,
            TopicSubscriptionRepository topicSubscriptionRepository,
            NotificationSendService notificationSendService) {
        this.accountRepository = accountRepository;
        this.topicSubscriptionRepository = topicSubscriptionRepository;
        this.notificationSendService = notificationSendService;
    }

    @Transactional
    public void sendNotification(AdminNotificationRequest request) {
        List<UUID> targetAccountIds = resolveTargets(request);
        log.info("[AdminNotification] 발송 대상: {} 명, targetType={}", targetAccountIds.size(), request.targetType());
        for (UUID accountId : targetAccountIds) {
            notificationSendService.enqueueSystem(accountId, request.title(), request.body());
        }
    }

    private List<UUID> resolveTargets(AdminNotificationRequest request) {
        return switch (request.targetType()) {
            case ALL -> accountRepository.findByStatus(AccountStatus.ACTIVE).stream()
                    .map(a -> a.getId())
                    .toList();
            case ACCOUNT_IDS -> request.accountIds() != null ? request.accountIds() : List.of();
            case TOPIC_SUBSCRIBERS -> topicSubscriptionRepository.findByIdTopic(request.topic()).stream()
                    .map(s -> s.getAccountId())
                    .distinct()
                    .toList();
        };
    }
}
