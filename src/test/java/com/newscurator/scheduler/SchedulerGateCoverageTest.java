package com.newscurator.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.client.ai.TtsProvider;
import com.newscurator.client.notification.EmailPort;
import com.newscurator.client.notification.PushNotificationPort;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.EmailSubscriptionRepository;
import com.newscurator.repository.NotificationRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.AiProcessingService;
import com.newscurator.service.BiasAnalysisService;
import com.newscurator.service.CollectionService;
import com.newscurator.service.ExpiryService;
import com.newscurator.service.NotificationSendService;
import com.newscurator.service.S3AudioUploader;
import com.newscurator.service.TrendAggregationService;
import com.newscurator.service.admin.SchedulerControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 크라운주얼 ① 스케줄러 게이트 커버리지(008 US3, FR-031) — 12개 @Scheduled 메서드 each.
 *
 * <p>각 메서드를 키 disabled 상태로 호출 → (a) 본문 skip(business 협력자 미호출) + (b) 진입 시
 * {@code isEnabled(key)} 호출(게이트 존재)을 단언. 한 메서드라도 게이트 누락 시 verifyNoInteractions
 * 또는 verify(isEnabled)가 깨진다. ★ weekly_email 포함(전역 @ConditionalOnProperty 없는 유일 스케줄러).
 *
 * <p>12 @Test = 12 @Scheduled 메서드 1:1(collection·ai_processing·bias_analysis·bias_recovery·bias_sla·
 * trend_aggregation·trend_cleanup·tts_processing·notification_outbox·notification_expiry·weekly_email·expiry).
 */
class SchedulerGateCoverageTest {

    /** key disabled로 mock 게이트 구성. */
    private SchedulerControlService disabled(String key) {
        SchedulerControlService c = mock(SchedulerControlService.class);
        when(c.isEnabled(key)).thenReturn(false);
        return c;
    }

    @Test
    @DisplayName("collection — CollectionScheduler.run skip")
    void collection() {
        CollectionService biz = mock(CollectionService.class);
        SchedulerControlService c = disabled("collection");
        new CollectionScheduler(biz, c).run();
        verify(c).isEnabled("collection");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("ai_processing — AiProcessingScheduler.run skip")
    void aiProcessing() {
        AiProcessingService biz = mock(AiProcessingService.class);
        SchedulerControlService c = disabled("ai_processing");
        new AiProcessingScheduler(biz, c).run();
        verify(c).isEnabled("ai_processing");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("bias_analysis — BiasAnalysisScheduler.run skip")
    void biasAnalysis() {
        BiasAnalysisService biz = mock(BiasAnalysisService.class);
        SchedulerControlService c = disabled("bias_analysis");
        new BiasAnalysisScheduler(biz, c).run();
        verify(c).isEnabled("bias_analysis");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("bias_recovery — BiasAnalysisScheduler.recover skip")
    void biasRecovery() {
        BiasAnalysisService biz = mock(BiasAnalysisService.class);
        SchedulerControlService c = disabled("bias_recovery");
        new BiasAnalysisScheduler(biz, c).recover();
        verify(c).isEnabled("bias_recovery");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("bias_sla — BiasAnalysisScheduler.emitSla skip")
    void biasSla() {
        BiasAnalysisService biz = mock(BiasAnalysisService.class);
        SchedulerControlService c = disabled("bias_sla");
        new BiasAnalysisScheduler(biz, c).emitSla();
        verify(c).isEnabled("bias_sla");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("trend_aggregation — TrendAggregationScheduler.run skip")
    void trendAggregation() {
        TrendAggregationService biz = mock(TrendAggregationService.class);
        SchedulerControlService c = disabled("trend_aggregation");
        new TrendAggregationScheduler(biz, c).run();
        verify(c).isEnabled("trend_aggregation");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("trend_cleanup — TrendAggregationScheduler.cleanup skip")
    void trendCleanup() {
        TrendAggregationService biz = mock(TrendAggregationService.class);
        SchedulerControlService c = disabled("trend_cleanup");
        new TrendAggregationScheduler(biz, c).cleanup();
        verify(c).isEnabled("trend_cleanup");
        verifyNoInteractions(biz);
    }

    @Test
    @DisplayName("tts_processing — TtsProcessingScheduler.process skip")
    void ttsProcessing() {
        TtsAudioClaimer claimer = mock(TtsAudioClaimer.class);
        TtsProvider ttsProvider = mock(TtsProvider.class);
        S3AudioUploader s3 = mock(S3AudioUploader.class);
        SummaryRepository summaryRepo = mock(SummaryRepository.class);
        NotificationSendService notif = mock(NotificationSendService.class);
        DailyBriefRepository dailyBrief = mock(DailyBriefRepository.class);
        SchedulerControlService c = disabled("tts_processing");
        new TtsProcessingScheduler(claimer, ttsProvider, s3, summaryRepo, notif, dailyBrief, 10, c)
                .process();
        verify(c).isEnabled("tts_processing");
        verifyNoInteractions(claimer, ttsProvider, s3, summaryRepo, notif, dailyBrief);
    }

    @Test
    @DisplayName("notification_outbox — NotificationOutboxProcessor.process skip")
    void notificationOutbox() {
        NotificationOutboxClaimer claimer = mock(NotificationOutboxClaimer.class);
        PushNotificationPort push = mock(PushNotificationPort.class);
        EmailPort email = mock(EmailPort.class);
        com.newscurator.service.DeviceTokenService dts =
                mock(com.newscurator.service.DeviceTokenService.class);
        ObjectMapper om = mock(ObjectMapper.class);
        SchedulerControlService c = disabled("notification_outbox");
        new NotificationOutboxProcessor(claimer, push, email, dts, om, 50, c).process();
        verify(c).isEnabled("notification_outbox");
        verifyNoInteractions(claimer, push, email, dts);
    }

    @Test
    @DisplayName("notification_expiry — NotificationExpiryScheduler.deleteExpiredNotifications skip")
    void notificationExpiry() {
        NotificationRepository repo = mock(NotificationRepository.class);
        SchedulerControlService c = disabled("notification_expiry");
        new NotificationExpiryScheduler(repo, c).deleteExpiredNotifications();
        verify(c).isEnabled("notification_expiry");
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("★ weekly_email — WeeklyEmailScheduler.scheduleWeeklyEmail skip (전역 토글 없는 유일 스케줄러)")
    void weeklyEmail() {
        EmailSubscriptionRepository repo = mock(EmailSubscriptionRepository.class);
        NotificationSendService notif = mock(NotificationSendService.class);
        SchedulerControlService c = disabled("weekly_email");
        new WeeklyEmailScheduler(repo, notif, c).scheduleWeeklyEmail();
        verify(c).isEnabled("weekly_email");
        verifyNoInteractions(repo, notif);
    }

    @Test
    @DisplayName("expiry — ExpiryScheduler.run skip")
    void expiry() {
        ExpiryService biz = mock(ExpiryService.class);
        SchedulerControlService c = disabled("expiry");
        new ExpiryScheduler(biz, c).run();
        verify(c).isEnabled("expiry");
        verifyNoInteractions(biz);
    }
}
