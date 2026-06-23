package com.newscurator.scheduler;

import com.newscurator.repository.NotificationRepository;
import java.time.Instant;
import com.newscurator.service.admin.SchedulerControlService;
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
    private final SchedulerControlService schedulerControl;

    public NotificationExpiryScheduler(
            NotificationRepository notificationRepository, SchedulerControlService schedulerControl) {
        this.notificationRepository = notificationRepository;
        this.schedulerControl = schedulerControl;
    }

    @Scheduled(cron = "${app.scheduler.notification.expiry-cron}", zone = "UTC")
    @Transactional
    public void deleteExpiredNotifications() {
        if (!schedulerControl.isEnabled("notification_expiry")) {
            return;
        }
        Instant now = Instant.now();
        int deleted = notificationRepository.deleteByExpiresAtBefore(now);
        log.info("[NOTIFICATION-EXPIRY] 만료 알림 삭제 완료: {}건", deleted);
    }
}
