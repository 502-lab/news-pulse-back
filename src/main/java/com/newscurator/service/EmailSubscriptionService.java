package com.newscurator.service;

import com.newscurator.domain.EmailSubscription;
import com.newscurator.domain.enums.EmailSubscriptionType;
import com.newscurator.exception.AlreadySubscribedException;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.repository.EmailSubscriptionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailSubscriptionService {

    private final EmailSubscriptionRepository emailSubscriptionRepository;

    public EmailSubscriptionService(EmailSubscriptionRepository emailSubscriptionRepository) {
        this.emailSubscriptionRepository = emailSubscriptionRepository;
    }

    @Transactional
    public EmailSubscription subscribe(UUID accountId, EmailSubscriptionType type) {
        if (emailSubscriptionRepository.existsByIdAccountIdAndIdTypeAndActiveTrue(accountId, type)) {
            throw new AlreadySubscribedException("Already subscribed: " + type);
        }
        return emailSubscriptionRepository.findByIdAccountIdAndIdType(accountId, type)
                .map(sub -> {
                    sub.activate();
                    return emailSubscriptionRepository.save(sub);
                })
                .orElseGet(() -> emailSubscriptionRepository.save(EmailSubscription.create(accountId, type)));
    }

    @Transactional
    public void unsubscribe(UUID accountId, EmailSubscriptionType type) {
        EmailSubscription sub = emailSubscriptionRepository.findByIdAccountIdAndIdType(accountId, type)
                .filter(EmailSubscription::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("EmailSubscription", accountId + ":" + type));
        sub.deactivate();
        emailSubscriptionRepository.save(sub);
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID accountId, EmailSubscriptionType type) {
        return emailSubscriptionRepository.existsByIdAccountIdAndIdTypeAndActiveTrue(accountId, type);
    }
}
