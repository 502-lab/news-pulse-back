package com.newscurator.scheduler;

import com.newscurator.service.CollectionService;
import java.util.UUID;
import com.newscurator.service.admin.SchedulerControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);

    private final CollectionService collectionService;
    private final SchedulerControlService schedulerControl;

    public CollectionScheduler(
            CollectionService collectionService, SchedulerControlService schedulerControl) {
        this.collectionService = collectionService;
        this.schedulerControl = schedulerControl;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.collection.interval-ms:900000}")
    public void run() {
        if (!schedulerControl.isEnabled("collection")) {
            return;
        }
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            log.info("[COLLECT-SCHEDULER] 수집 스케줄러 시작, runId={}", runId);
            collectionService.collectAll();
            log.info("[COLLECT-SCHEDULER] 수집 스케줄러 완료, runId={}", runId);
        } catch (Exception e) {
            log.error("[COLLECT-SCHEDULER] 수집 스케줄러 실패, runId={}: {}", runId, e.getMessage(), e);
        } finally {
            MDC.remove("runId");
        }
    }
}
