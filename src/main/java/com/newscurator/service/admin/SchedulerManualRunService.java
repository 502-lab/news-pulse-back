package com.newscurator.service.admin;

import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.NotificationRepository;
import com.newscurator.scheduler.NotificationOutboxProcessor;
import com.newscurator.scheduler.TtsProcessingScheduler;
import com.newscurator.scheduler.WeeklyEmailScheduler;
import com.newscurator.service.AiProcessingService;
import com.newscurator.service.BiasAnalysisService;
import com.newscurator.service.CollectionService;
import com.newscurator.service.ExpiryService;
import com.newscurator.service.TrendAggregationService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 어드민 스케줄러 수동 실행(008 FR-030, T048).
 *
 * <p>★ 게이트 우회: 수동 실행은 {@link SchedulerControlService#isEnabled} 게이트를 거치지 않는
 * 작업 진입점(서비스 메서드 또는 스케줄러 {@code runNow()})을 직접 호출한다. 즉 토글로 꺼둔(disabled)
 * 스케줄러도 admin이 1회 임시로 돌릴 수 있다 — "꺼둔 걸 임시로 돌림"이 수동 실행의 목적이기 때문.
 * 실행 후 {@code SCHEDULER_RUN} 감사를 남긴다.
 *
 * <p>Tts/Outbox 스케줄러는 {@code @ConditionalOnProperty(app.scheduler.enabled)}라 비활성 환경에서
 * 빈이 없을 수 있어 {@link ObjectProvider}로 지연 주입한다.
 */
@Service
public class SchedulerManualRunService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerManualRunService.class);
    private static final String ACTION_RUN = "SCHEDULER_RUN";

    private final Map<String, Runnable> runners;
    private final AdminAuditService auditService;

    public SchedulerManualRunService(
            CollectionService collectionService,
            AiProcessingService aiProcessingService,
            BiasAnalysisService biasAnalysisService,
            TrendAggregationService trendAggregationService,
            ExpiryService expiryService,
            NotificationRepository notificationRepository,
            WeeklyEmailScheduler weeklyEmailScheduler,
            ObjectProvider<TtsProcessingScheduler> ttsProvider,
            ObjectProvider<NotificationOutboxProcessor> outboxProvider,
            AdminAuditService auditService) {
        this.auditService = auditService;
        Map<String, Runnable> m = new LinkedHashMap<>();
        m.put("collection", collectionService::collectAll);
        m.put("ai_processing", aiProcessingService::processBatch);
        m.put("bias_analysis", biasAnalysisService::processBatch);
        m.put("bias_recovery", biasAnalysisService::recoverOneShotFailed);
        m.put("bias_sla", biasAnalysisService::emitDailySlaMetrics);
        m.put("trend_aggregation", trendAggregationService::aggregate);
        m.put("trend_cleanup", trendAggregationService::cleanup);
        m.put("notification_expiry",
                () -> notificationRepository.deleteByExpiresAtBefore(Instant.now()));
        m.put("weekly_email", weeklyEmailScheduler::runNow);
        m.put("expiry",
                () -> {
                    expiryService.hideExpiredArticles();
                    expiryService.deleteGracePeriodExpired();
                });
        // 조건부 빈 — 지연 해소(비활성 환경에선 호출 시점에 부재 가능)
        m.put("tts_processing", () -> ttsProvider.getObject().runNow());
        m.put("notification_outbox", () -> outboxProvider.getObject().runNow());
        this.runners = m;
    }

    /** 수동 실행 가능한 scheduler_key 집합. */
    public Set<String> availableKeys() {
        return runners.keySet();
    }

    /**
     * 스케줄러 1회 수동 실행(게이트 우회) + 감사. 알 수 없는 키는 404.
     */
    public void runManually(UUID actorId, String schedulerKey) {
        Runnable r = runners.get(schedulerKey);
        if (r == null) {
            throw new AdminTargetNotFoundException("알 수 없는 스케줄러 키: " + schedulerKey);
        }
        log.info("[ADMIN] 스케줄러 수동 실행(게이트 우회): key={}, actor={}", schedulerKey, actorId);
        r.run();
        auditService.record(
                actorId, ACTION_RUN, AuditTargetType.SCHEDULER, schedulerKey, Map.of("manual", true));
    }
}
