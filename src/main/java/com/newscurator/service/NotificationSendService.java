package com.newscurator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.Notification;
import com.newscurator.domain.NotificationOutbox;
import com.newscurator.domain.enums.NotificationChannel;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.DeviceTokenRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationSendService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendService.class);

    private final NotificationService notificationService;
    private final NotificationOutboxRepository outboxRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final AccountRepository accountRepository;
    private final NotificationPreferencesService preferencesService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public NotificationSendService(
            NotificationService notificationService,
            NotificationOutboxRepository outboxRepository,
            DeviceTokenRepository deviceTokenRepository,
            AccountRepository accountRepository,
            NotificationPreferencesService preferencesService,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.outboxRepository = outboxRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.accountRepository = accountRepository;
        this.preferencesService = preferencesService;
        this.transactionManager = transactionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * PUSH 채널 outbox row 단건 enqueue.
     * idempotency_key = PUSH:{accountId}:{notificationId}
     * UNIQUE 위반(중복) 시 조용히 무시.
     */
    @Transactional
    public void enqueuePush(UUID accountId, Long notificationId, String token, String title, String body) {
        String key = "PUSH:" + accountId + ":" + notificationId;
        String payload = buildPayload(Map.of("token", token, "title", title, "body", body));
        insertOutbox(accountId, notificationId, NotificationChannel.PUSH, payload, key);
    }

    /**
     * 008 어드민 푸시 enqueue — 호출자가 제공한 <b>결정적 idempotency_key</b>로 PUSH outbox 단건.
     * 같은 키 재발송은 uq_outbox_idempotency로 무시(멱등). 공지=ADMIN:NOTICE:{noticeId}:{accountId},
     * 캠페인=ADMIN:CAMPAIGN:{serverUuid}:{accountId}(매 발송 고유 UUID). 디바이스 토큰 없으면 skip.
     */
    @Transactional
    public void enqueueAdminPush(UUID accountId, String title, String body, String idempotencyKey) {
        // 인앱(FR-042): 토큰 무관 전원 도달 + dedup_key로 멱등(같은 키 재발송 무시)
        notificationService.createSystemNotificationIdempotent(accountId, title, body, idempotencyKey);
        // 푸시: 디바이스 토큰 보유자만(토큰 없으면 푸시 skip), outbox uq_outbox_idempotency로 멱등
        deviceTokenRepository.findByAccountId(accountId).stream()
                .findFirst()
                .ifPresent(
                        dt -> {
                            String payload =
                                    buildPayload(
                                            Map.of("token", dt.getToken(), "title", title, "body", body));
                            insertOutbox(
                                    accountId, null, NotificationChannel.PUSH, payload, idempotencyKey);
                        });
    }

    /**
     * 속보(BREAKING) 알림 enqueue.
     * 인앱 알림 생성 + PUSH outbox enqueue (토큰이 없으면 인앱만 생성).
     */
    @Transactional
    public void enqueueBreaking(UUID accountId, Long articleId) {
        Notification n = notificationService.createNotification(
                accountId, NotificationType.BREAKING, "속보", "새로운 속보가 도착했습니다.", String.valueOf(articleId));
        enqueueFirstDeviceToken(accountId, n.getId(), n.getTitle(), n.getBody());
    }

    /**
     * AI 브리핑 알림 enqueue.
     */
    @Transactional
    public void enqueueBriefing(UUID accountId) {
        Notification n = notificationService.createNotification(
                accountId, NotificationType.BRIEFING, "오늘의 AI 브리핑", "브리핑이 준비됐어요.", null);
        enqueueFirstDeviceToken(accountId, n.getId(), n.getTitle(), n.getBody());
    }

    /**
     * TTS 완료 알림 enqueue. articleId+voiceId로 관련 계정을 찾지 않고 직접 accountId를 받는다.
     */
    @Transactional
    public void enqueueTtsReady(UUID accountId, String ttsRefId) {
        Notification n = notificationService.createNotification(
                accountId, NotificationType.TTS_READY, "TTS 변환 완료", "오디오 브리핑을 들을 수 있어요.", ttsRefId);
        enqueueFirstDeviceToken(accountId, n.getId(), n.getTitle(), n.getBody());
    }

    /**
     * 시스템 알림 enqueue (US5 어드민 발송 등).
     */
    @Transactional
    public void enqueueSystem(UUID accountId, String title, String body) {
        Notification n = notificationService.createNotification(accountId, NotificationType.SYSTEM, title, body, null);
        enqueueFirstDeviceToken(accountId, n.getId(), title, body);
    }

    /**
     * 주간 이메일 enqueue.
     * idempotency_key = EMAIL:WEEKLY:{accountId}:{yearWeek}
     */
    @Transactional
    public void enqueueWeeklyEmail(UUID accountId, String yearWeek) {
        String key = "EMAIL:WEEKLY:" + accountId + ":" + yearWeek;
        String email = accountRepository.findById(accountId)
                .map(a -> a.getEmail())
                .orElse(null);
        if (email == null) {
            log.warn("[NOTIFICATION] enqueueWeeklyEmail: account {} not found, skip", accountId);
            return;
        }
        String payload = buildPayload(Map.of(
                "to", email,
                "subject", "이번 주 AI 뉴스 브리핑",
                "htmlBody", "<p>이번 주 뉴스 브리핑입니다.</p>"));
        insertOutbox(accountId, null, NotificationChannel.EMAIL, payload, key);
    }

    private void enqueueFirstDeviceToken(UUID accountId, Long notificationId, String title, String body) {
        if (!preferencesService.getOrDefault(accountId).isPushEnabled()) {
            log.debug("[NOTIFICATION] push disabled for accountId={}, skip outbox enqueue", accountId);
            return;
        }
        deviceTokenRepository.findByAccountId(accountId).stream()
                .findFirst()
                .ifPresent(dt -> enqueuePush(accountId, notificationId, dt.getToken(), title, body));
    }

    private void insertOutbox(UUID accountId, Long notificationId, NotificationChannel channel,
                               String payload, String idempotencyKey) {
        // REQUIRES_NEW: UNIQUE 위반 시 내부 TX만 롤백, 호출자 TX는 오염되지 않음
        TransactionTemplate tmpl = new TransactionTemplate(transactionManager);
        tmpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tmpl.execute(status -> {
                NotificationOutbox outbox = NotificationOutbox.builder()
                        .accountId(accountId)
                        .notificationId(notificationId)
                        .channel(channel)
                        .payload(payload)
                        .idempotencyKey(idempotencyKey)
                        .build();
                outboxRepository.save(outbox);
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            log.debug("[NOTIFICATION] 중복 enqueue 무시: key={}", idempotencyKey);
        }
    }

    private String buildPayload(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
