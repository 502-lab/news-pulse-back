package com.newscurator.service;

import com.newscurator.domain.Notification;
import com.newscurator.domain.enums.NotificationType;
import com.newscurator.dto.response.NotificationResponse;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Notification createNotification(UUID accountId, NotificationType type,
                                           String title, String body, String referenceId) {
        Notification notification = Notification.builder()
                .accountId(accountId)
                .type(type)
                .title(title)
                .body(body)
                .referenceId(referenceId)
                .build();
        return notificationRepository.save(notification);
    }

    /**
     * 008 FR-042: 어드민 SYSTEM 인앱 알림 멱등 생성. 같은 dedupKey 재발송은 무시(ON CONFLICT). 토큰 무관 전원.
     */
    @Transactional
    public void createSystemNotificationIdempotent(
            UUID accountId, String title, String body, String dedupKey) {
        notificationRepository.insertSystemIdempotent(accountId, title, body, dedupKey);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listNotifications(UUID accountId, boolean unread, Pageable pageable) {
        Page<Notification> page = unread
                ? notificationRepository.findByAccountIdAndIsReadFalseOrderByCreatedAtDesc(accountId, pageable)
                : notificationRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
        return page.map(NotificationResponse::from);
    }

    @Transactional
    public NotificationResponse markRead(UUID accountId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndAccountId(notificationId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
        notification.markRead();
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Transactional
    public void markAllRead(UUID accountId) {
        notificationRepository.markAllReadByAccountId(accountId);
    }
}
