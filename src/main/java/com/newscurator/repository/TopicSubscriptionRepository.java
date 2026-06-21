package com.newscurator.repository;

import com.newscurator.domain.TopicSubscription;
import com.newscurator.domain.TopicSubscriptionId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicSubscriptionRepository extends JpaRepository<TopicSubscription, TopicSubscriptionId> {

    List<TopicSubscription> findByIdAccountId(UUID accountId);

    @Modifying
    @Query("DELETE FROM TopicSubscription t WHERE t.id.accountId = :accountId")
    void deleteAllByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT t FROM TopicSubscription t WHERE t.id.topic = :topic")
    List<TopicSubscription> findByIdTopic(@Param("topic") com.newscurator.domain.enums.NotificationTopic topic);
}
