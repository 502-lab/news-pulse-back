package com.newscurator.repository;

import com.newscurator.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    Page<Notification> findByAccountIdAndIsReadFalseOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    Optional<Notification> findByIdAndAccountId(Long id, UUID accountId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.accountId = :accountId AND n.isRead = false")
    int markAllReadByAccountId(@Param("accountId") UUID accountId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.expiresAt < :now")
    int deleteByExpiresAtBefore(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE Notification n SET n.expiresAt = :expiresAt WHERE n.id = :id")
    void updateExpiresAt(@Param("id") Long id, @Param("expiresAt") Instant expiresAt);

    /**
     * 008 FR-042: 어드민 SYSTEM 인앱 알림 멱등 삽입. 같은 dedup_key 재발송은
     * 부분 unique 인덱스(uq_notification_dedup)로 무시(ON CONFLICT DO NOTHING). 토큰 무관 전원 도달.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO notifications (account_id, type, title, body, dedup_key, created_at, expires_at)"
                        + " VALUES (CAST(:accountId AS uuid), 'SYSTEM', :title, :body, :dedupKey, NOW(),"
                        + " NOW() + INTERVAL '90 days')"
                        + " ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING",
            nativeQuery = true)
    void insertSystemIdempotent(
            @Param("accountId") UUID accountId,
            @Param("title") String title,
            @Param("body") String body,
            @Param("dedupKey") String dedupKey);
}
