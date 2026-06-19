package com.newscurator.repository;

import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.EmailSubscriptionId;
import com.newscurator.domain.enums.EmailSubscriptionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSubscriptionRepository extends JpaRepository<EmailSubscription, EmailSubscriptionId> {

    Optional<EmailSubscription> findByIdAccountIdAndIdType(UUID accountId, EmailSubscriptionType type);

    boolean existsByIdAccountIdAndIdTypeAndActiveTrue(UUID accountId, EmailSubscriptionType type);

    List<EmailSubscription> findByIdTypeAndActiveTrue(EmailSubscriptionType type);
}
