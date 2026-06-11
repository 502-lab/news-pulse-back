package com.newscurator.scheduler;

import com.newscurator.service.AiProcessingService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 단일 EC2 전제 (research #13): fixedDelay로 JVM 내 중첩 실행 없음.
// scale-out 시 ShedLock 활성화:
// @SchedulerLock(name = "AiProcessingScheduler_run", lockAtLeastFor = "PT50S", lockAtMostFor = "PT5M")
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AiProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingScheduler.class);

    private final AiProcessingService aiProcessingService;

    public AiProcessingScheduler(AiProcessingService aiProcessingService) {
        this.aiProcessingService = aiProcessingService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.ai.interval-ms:60000}")
    public void run() {
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            log.info("[AI-SCHEDULER] AI 처리 스케줄러 시작, runId={}", runId);
            aiProcessingService.processBatch();
            log.info("[AI-SCHEDULER] AI 처리 스케줄러 완료, runId={}", runId);
        } catch (Exception e) {
            log.error("[AI-SCHEDULER] AI 처리 스케줄러 실패, runId={}: {}", runId, e.getMessage(), e);
        } finally {
            MDC.remove("runId");
        }
    }
}
