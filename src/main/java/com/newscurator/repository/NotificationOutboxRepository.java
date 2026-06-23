package com.newscurator.repository;

import com.newscurator.domain.NotificationOutbox;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /**
     * PENDING 행을 FOR UPDATE SKIP LOCKED로 클레임.
     * 같은 트랜잭션 내에서 호출자가 PROCESSING으로 마킹해야 한다.
     */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING'
              AND next_retry_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationOutbox> findPendingWithLock(@Param("limit") int limit);

    /** 008 US5 ErrorLog: 발송 실패(FAILED) outbox 수. */
    @Query(value = "SELECT COUNT(*) FROM notification_outbox WHERE status = 'FAILED'", nativeQuery = true)
    long countFailed();
}
