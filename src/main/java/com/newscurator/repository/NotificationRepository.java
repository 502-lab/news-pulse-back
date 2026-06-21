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
}
