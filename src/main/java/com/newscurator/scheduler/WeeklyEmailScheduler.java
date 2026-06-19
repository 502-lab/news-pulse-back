package com.newscurator.scheduler;

import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.enums.EmailSubscriptionType;
import com.newscurator.repository.EmailSubscriptionRepository;
import com.newscurator.service.NotificationSendService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WeeklyEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyEmailScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EmailSubscriptionRepository emailSubscriptionRepository;
    private final NotificationSendService notificationSendService;

    public WeeklyEmailScheduler(
            EmailSubscriptionRepository emailSubscriptionRepository,
            NotificationSendService notificationSendService) {
        this.emailSubscriptionRepository = emailSubscriptionRepository;
        this.notificationSendService = notificationSendService;
    }

    @Scheduled(cron = "${app.weekly-email.cron}", zone = "UTC")
    public void scheduleWeeklyEmail() {
        String yearWeek = currentYearWeek();
        log.info("[WeeklyEmail] 발송 시작: yearWeek={}", yearWeek);
        sendWeeklyEmailForWeek(yearWeek);
    }

    public void sendWeeklyEmailForWeek(String yearWeek) {
        List<EmailSubscription> subscribers =
                emailSubscriptionRepository.findByIdTypeAndActiveTrue(EmailSubscriptionType.WEEKLY_EMAIL);
        log.info("[WeeklyEmail] 구독자 수: {}, yearWeek={}", subscribers.size(), yearWeek);
        for (EmailSubscription sub : subscribers) {
            notificationSendService.enqueueWeeklyEmail(sub.getAccountId(), yearWeek);
        }
    }

    private String currentYearWeek() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        // ISO 8601 week format: yyyy-Www (e.g., 2026-W24)
        int year = now.getYear();
        int week = now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        // adjust year for week 1 in Jan or week 52/53 in Dec
        if (now.getMonth().getValue() == 1 && week >= 52) {
            year = year - 1;
        } else if (now.getMonth().getValue() == 12 && week == 1) {
            year = year + 1;
        }
        return String.format("%d-W%02d", year, week);
    }
}
