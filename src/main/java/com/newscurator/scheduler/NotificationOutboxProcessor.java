package com.newscurator.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.client.notification.EmailPort;
import com.newscurator.client.notification.PushNotificationPort;
import com.newscurator.domain.NotificationOutbox;
import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.exception.FcmUnregisteredException;
import com.newscurator.service.DeviceTokenService;
import java.util.List;
import java.util.Map;
import com.newscurator.service.admin.SchedulerControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * outbox 처리 스케줄러.
 *
 * 트랜잭션 경계 설계:
 * Phase 1 — NotificationOutboxClaimer.claimBatch() (@Transactional 별도 빈):
 *            PENDING → PROCESSING 마킹 + 커밋 → FOR UPDATE SKIP LOCKED 락 해제.
 *            멀티 인스턴스 중복 처리 방지.
 * Phase 2 — FCM/Email HTTP 호출: 락 해제 후 실행 (외부 호출, DB 락 불점유).
 * Phase 3 — NotificationOutboxClaimer.persistResult(): 결과 저장 (SENT/PENDING/FAILED).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxProcessor.class);

    private final NotificationOutboxClaimer claimer;
    private final PushNotificationPort pushPort;
    private final EmailPort emailPort;
    private final DeviceTokenService deviceTokenService;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final SchedulerControlService schedulerControl;

    public NotificationOutboxProcessor(
            NotificationOutboxClaimer claimer,
            PushNotificationPort pushPort,
            EmailPort emailPort,
            DeviceTokenService deviceTokenService,
            ObjectMapper objectMapper,
            @Value("${app.scheduler.notification.outbox-batch-size:50}") int batchSize,
            SchedulerControlService schedulerControl) {
        this.claimer = claimer;
        this.pushPort = pushPort;
        this.emailPort = emailPort;
        this.deviceTokenService = deviceTokenService;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.schedulerControl = schedulerControl;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.notification.outbox-interval-ms}")
    public void process() {
        if (!schedulerControl.isEnabled("notification_outbox")) {
            return;
        }
        runNow();
    }

    /** 게이트 우회 수동 실행용 — 작업 본문(admin manual run). */
    public void runNow() {
        // Phase 1: 클레임 — TX 커밋 시 락 해제
        List<NotificationOutbox> batch = claimer.claimBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.info("[OUTBOX] 배치 클레임: {}건", batch.size());

        // Phase 2+3: 각 항목 독립 처리 — HTTP 호출은 락 밖에서 실행
        for (NotificationOutbox outbox : batch) {
            processOne(outbox);
        }
    }

    private void processOne(NotificationOutbox outbox) {
        try {
            Map<String, String> payload = parsePayload(outbox.getPayload());
            if (outbox.getChannel() == NotificationChannel.PUSH) {
                pushPort.send(payload.get("token"), payload.get("title"), payload.get("body"));
            } else {
                emailPort.send(payload.get("to"), payload.get("subject"), payload.get("htmlBody"));
            }
            outbox.markSent();
            log.debug("[OUTBOX] SENT: id={}", outbox.getId());
        } catch (FcmUnregisteredException e) {
            deviceTokenService.deleteByToken(e.getToken());
            outbox.markFailed();
            log.info("[OUTBOX] FCM token unregistered, token removed, id={}", outbox.getId());
        } catch (Exception e) {
            outbox.incrementAttemptWithBackoff();
            log.warn("[OUTBOX] 발송 실패 (attempt={}): id={}, msg={}",
                    outbox.getAttemptCount(), outbox.getId(), e.getMessage());
        } finally {
            claimer.persistResult(outbox);
        }
    }

    private Map<String, String> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 파싱 실패", e);
        }
    }
}
