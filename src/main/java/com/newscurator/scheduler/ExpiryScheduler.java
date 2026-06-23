package com.newscurator.scheduler;

import com.newscurator.service.ExpiryService;
import com.newscurator.service.admin.SchedulerControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final ExpiryService expiryService;
    private final SchedulerControlService schedulerControl;

    public ExpiryScheduler(
            ExpiryService expiryService, SchedulerControlService schedulerControl) {
        this.expiryService = expiryService;
        this.schedulerControl = schedulerControl;
    }

    @Scheduled(cron = "0 0 3 * * *") // 새벽 3시 (UTC) 일 1회
    public void run() {
        if (!schedulerControl.isEnabled("expiry")) {
            return;
        }
        log.info("[EXPIRY-SCHEDULER] 만료 처리 시작");
        try {
            expiryService.hideExpiredArticles();
            expiryService.deleteGracePeriodExpired();
            log.info("[EXPIRY-SCHEDULER] 만료 처리 완료");
        } catch (Exception e) {
            log.error("[EXPIRY-SCHEDULER] 만료 처리 실패: {}", e.getMessage(), e);
        }
    }
}
