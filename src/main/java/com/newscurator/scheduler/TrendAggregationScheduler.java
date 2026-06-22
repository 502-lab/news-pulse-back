package com.newscurator.scheduler;

import com.newscurator.service.TrendAggregationService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 트렌드 집계 스케줄러. 단일 EC2 fixedDelay 전제(AiProcessingScheduler 동일).
 * multi-instance 시 ShedLock 필요.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TrendAggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrendAggregationScheduler.class);

    private final TrendAggregationService trendAggregationService;

    public TrendAggregationScheduler(TrendAggregationService trendAggregationService) {
        this.trendAggregationService = trendAggregationService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.trend.interval-ms:600000}")
    public void run() {
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            trendAggregationService.aggregate();
        } catch (Exception e) {
            log.error("[TREND-SCHEDULER] 집계 실패, runId={}: {}", runId, e.getMessage(), e);
        } finally {
            MDC.remove("runId");
        }
    }
}
