package com.newscurator.scheduler;

import com.newscurator.repository.NotificationRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationExpiryScheduler.class);

    private final NotificationRepository notificationRepository;

    public NotificationExpiryScheduler(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Scheduled(cron = "${app.scheduler.notification.expiry-cron}", zone = "UTC")
    @Transactional
    public void deleteExpiredNotifications() {
        Instant now = Instant.now();
        int deleted = notificationRepository.deleteByExpiresAtBefore(now);
        log.info("[NOTIFICATION-EXPIRY] 만료 알림 삭제 완료: {}건", deleted);
    }
}
