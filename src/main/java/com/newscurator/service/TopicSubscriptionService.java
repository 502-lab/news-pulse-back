package com.newscurator.service;

import com.newscurator.domain.TopicSubscription;
import com.newscurator.domain.enums.NotificationTopic;
import com.newscurator.dto.response.TopicSubscriptionsResponse;
import com.newscurator.repository.TopicSubscriptionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopicSubscriptionService {

    private final TopicSubscriptionRepository topicSubscriptionRepository;

    public TopicSubscriptionService(TopicSubscriptionRepository topicSubscriptionRepository) {
        this.topicSubscriptionRepository = topicSubscriptionRepository;
    }

    @Transactional(readOnly = true)
    public TopicSubscriptionsResponse getTopics(UUID accountId) {
        List<NotificationTopic> topics = topicSubscriptionRepository
                .findByIdAccountId(accountId)
                .stream()
                .map(TopicSubscription::getTopic)
                .toList();
        return new TopicSubscriptionsResponse(topics);
    }

    /**
     * 기존 구독 전부 삭제 후 신규 목록으로 교체 (replace-all).
     */
    @Transactional
    public TopicSubscriptionsResponse replaceAll(UUID accountId, List<NotificationTopic> topics) {
        topicSubscriptionRepository.deleteAllByAccountId(accountId);
        List<TopicSubscription> newSubs = topics.stream()
                .distinct()
                .map(topic -> new TopicSubscription(accountId, topic))
                .toList();
        topicSubscriptionRepository.saveAll(newSubs);
        return new TopicSubscriptionsResponse(topics.stream().distinct().toList());
    }
}
