package com.newscurator.scheduler;

import com.newscurator.domain.NotificationOutbox;
import com.newscurator.repository.NotificationOutboxRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox 배치 클레임 전담 컴포넌트.
 *
 * 별도 Spring 빈으로 분리하여 @Transactional이 AOP 프록시를 통해 정상 적용되도록 한다.
 * claimBatch()의 TX가 커밋되면 FOR UPDATE SKIP LOCKED 락이 해제되고,
 * 이후 FCM/Email HTTP 호출이 DB 락 없이 실행된다.
 */
@Component
class NotificationOutboxClaimer {

    private final NotificationOutboxRepository outboxRepository;

    NotificationOutboxClaimer(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * PENDING 배치를 PROCESSING으로 마킹하고 커밋한다.
     * 반환 시점에 FOR UPDATE 락이 해제된다.
     */
    @Transactional
    public List<NotificationOutbox> claimBatch(int limit) {
        List<NotificationOutbox> batch = outboxRepository.findPendingWithLock(limit);
        for (NotificationOutbox outbox : batch) {
            outbox.markProcessing();
            outboxRepository.save(outbox);
        }
        return new ArrayList<>(batch);
    }

    /**
     * 처리 결과(SENT / PENDING(retry) / FAILED)를 자체 트랜잭션으로 저장한다.
     */
    @Transactional
    public void persistResult(NotificationOutbox outbox) {
        outboxRepository.save(outbox);
    }
}
