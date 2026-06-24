package com.newscurator.scheduler;

import com.newscurator.service.BiasAnalysisService;
import java.util.UUID;
import com.newscurator.service.admin.SchedulerControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 편향 분석 스케줄러 진입점.
 *
 * <p>단일 EC2 전제(AiProcessingScheduler 동일): fixedDelay로 JVM 내 중첩 실행 없음.
 * scale-out 시 ShedLock 활성화 고려.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BiasAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(BiasAnalysisScheduler.class);

    private final BiasAnalysisService biasAnalysisService;
    private final SchedulerControlService schedulerControl;

    public BiasAnalysisScheduler(
            BiasAnalysisService biasAnalysisService, SchedulerControlService schedulerControl) {
        this.biasAnalysisService = biasAnalysisService;
        this.schedulerControl = schedulerControl;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.bias.interval-ms:60000}")
    public void run() {
        if (!schedulerControl.isEnabled("bias_analysis")) {
            return;
        }
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            biasAnalysisService.processBatch();
        } catch (Exception e) {
            log.error("[BIAS-SCHEDULER] 배치 처리 실패, runId={}: {}", runId, e.getMessage(), e);
        } finally {
            MDC.remove("runId");
        }
    }

    @Scheduled(fixedDelayString = "${app.scheduler.bias.recovery-interval-ms:3600000}")
    public void recover() {
        if (!schedulerControl.isEnabled("bias_recovery")) {
            return;
        }
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            biasAnalysisService.recoverOneShotFailed();
        } catch (Exception e) {
            log.error("[BIAS-SCHEDULER] one-shot 복구 실패, runId={}: {}", runId, e.getMessage(), e);
        } finally {
            MDC.remove("runId");
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void emitSla() {
        if (!schedulerControl.isEnabled("bias_sla")) {
            return;
        }
        try {
            biasAnalysisService.emitDailySlaMetrics();
        } catch (Exception e) {
            log.error("[BIAS-SCHEDULER] SLA emit 실패: {}", e.getMessage(), e);
        }
    }
}
