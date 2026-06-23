package com.newscurator.service.admin;

import com.newscurator.domain.Notice;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.NoticeRepository;
import com.newscurator.service.NotificationSendService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 푸시 발송(008 FR-042). 005 파이프라인(enqueueAdminPush) 재사용 + 결정적 dedup 키.
 *
 * <ul>
 *   <li><b>공지 푸시</b>: 키 {@code ADMIN:NOTICE:{noticeId}:{accountId}} — 같은 공지 재발송은 멱등(무시).</li>
 *   <li><b>캠페인 푸시</b>: 키 {@code ADMIN:CAMPAIGN:{serverUuid}:{accountId}} — 매 발송 새 UUID라 의도적 재발송 가능.</li>
 * </ul>
 */
@Service
public class AdminPushService {

    private static final String ACTION_PUSH = "PUSH_SEND";

    private final NotificationSendService notificationSendService;
    private final NoticeRepository noticeRepository;
    private final AccountRepository accountRepository;
    private final AdminAuditService auditService;

    public AdminPushService(
            NotificationSendService notificationSendService,
            NoticeRepository noticeRepository,
            AccountRepository accountRepository,
            AdminAuditService auditService) {
        this.notificationSendService = notificationSendService;
        this.noticeRepository = noticeRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /** 공지 푸시 — 활성 사용자 전체. 키 ADMIN:NOTICE:{noticeId}:{accountId}(멱등). */
    @Transactional
    public void sendNoticePush(UUID actorId, Long noticeId) {
        Notice notice =
                noticeRepository
                        .findById(noticeId)
                        .orElseThrow(() -> new AdminTargetNotFoundException("공지를 찾을 수 없습니다: " + noticeId));
        for (UUID accountId : activeTargets()) {
            notificationSendService.enqueueAdminPush(
                    accountId,
                    notice.getTitle(),
                    notice.getContent(),
                    "ADMIN:NOTICE:" + noticeId + ":" + accountId);
        }
        auditService.record(
                actorId, ACTION_PUSH, AuditTargetType.PUSH, "NOTICE:" + noticeId,
                Map.of("kind", "NOTICE", "noticeId", noticeId));
    }

    /** 캠페인 푸시 — 활성 사용자 전체. 키 ADMIN:CAMPAIGN:{serverUuid}:{accountId}(매 발송 고유). */
    @Transactional
    public void sendCampaignPush(UUID actorId, String title, String body) {
        String campaignId = UUID.randomUUID().toString(); // 서버 생성 — 매 발송 고유
        for (UUID accountId : activeTargets()) {
            notificationSendService.enqueueAdminPush(
                    accountId, title, body, "ADMIN:CAMPAIGN:" + campaignId + ":" + accountId);
        }
        auditService.record(
                actorId, ACTION_PUSH, AuditTargetType.PUSH, "CAMPAIGN:" + campaignId,
                Map.of("kind", "CAMPAIGN", "campaignId", campaignId));
    }

    private List<UUID> activeTargets() {
        return accountRepository.findByStatus(AccountStatus.ACTIVE).stream().map(a -> a.getId()).toList();
    }
}
