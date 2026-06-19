package com.newscurator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.TopicSubscription;
import com.newscurator.domain.UserInterests;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.NotificationTopic;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.TopicSubscriptionRepository;
import com.newscurator.repository.UserInterestsRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProcessingTriggerTest {

    @Mock private AiProvider aiProvider;
    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private NotificationSendService notificationSendService;
    @Mock private TopicSubscriptionRepository topicSubscriptionRepository;
    @Mock private UserInterestsRepository userInterestsRepository;

    private AiProcessingService aiProcessingService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Long ARTICLE_ID = 100L;

    @BeforeEach
    void setUp() {
        AiProperties.DeepRetryProperties deepRetry = new AiProperties.DeepRetryProperties(60, 5);
        AiProperties aiProperties = new AiProperties(10, 3, 0L, deepRetry);
        SummaryService summaryService = new SummaryService(aiProperties);
        aiProcessingService = new AiProcessingService(
                aiProvider, articleRepository, summaryRepository, summaryService, aiProperties,
                notificationSendService, topicSubscriptionRepository, userInterestsRepository);

        when(summaryRepository.save(any(Summary.class))).thenAnswer(inv -> inv.getArgument(0));
        when(summaryRepository.findByArticleIdAndDepth(anyLong(), any())).thenReturn(Optional.empty());
        when(articleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Article buildArticle(Long id, ProcessingStatus categoryStatus, Category category) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(id);
        when(article.getCategoryStatus()).thenReturn(categoryStatus);
        when(article.getCategory()).thenReturn(category);
        when(article.getSummaryStatus()).thenReturn(ProcessingStatus.PENDING);
        when(article.getSummaryRetryCount()).thenReturn(0);
        return article;
    }

    private TopicSubscription buildSubscription(UUID accountId) {
        TopicSubscription sub = mock(TopicSubscription.class);
        when(sub.getAccountId()).thenReturn(accountId);
        return sub;
    }

    private UserInterests buildInterest(String category) {
        UserInterests ui = mock(UserInterests.class);
        when(ui.getCategory()).thenReturn(category);
        return ui;
    }

    // ─────────────────────────────────────────────────────────
    // (1) BREAKING 구독자 + 관심사 매칭 → enqueueBreaking 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BREAKING 구독자 + 관심사(POLITICS) 매칭 → enqueueBreaking 1회 호출")
    void processArticle_breakingSubscriberWithMatchingInterest_enqueuesBreaking() {
        Article article = buildArticle(ARTICLE_ID, ProcessingStatus.COMPLETED, Category.POLITICS);

        TopicSubscription sub = buildSubscription(ACCOUNT_ID);
        when(topicSubscriptionRepository.findByIdTopic(NotificationTopic.BREAKING))
                .thenReturn(List.of(sub));
        UserInterests ui = buildInterest("POLITICS");
        when(userInterestsRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(ui));

        when(aiProvider.summarize(any(), any(), eq(SummaryDepth.BALANCED))).thenReturn("summary");

        aiProcessingService.processArticle(article);

        verify(notificationSendService, times(1)).enqueueBreaking(ACCOUNT_ID, ARTICLE_ID);
    }

    // ─────────────────────────────────────────────────────────
    // (2) BREAKING 구독자 있지만 관심사 불일치 → enqueueBreaking 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BREAKING 구독자 있지만 관심사 불일치 → enqueueBreaking 0회")
    void processArticle_breakingSubscriberNoMatchingInterest_noEnqueue() {
        Article article = buildArticle(ARTICLE_ID, ProcessingStatus.COMPLETED, Category.SPORTS);

        TopicSubscription sub = buildSubscription(ACCOUNT_ID);
        when(topicSubscriptionRepository.findByIdTopic(NotificationTopic.BREAKING))
                .thenReturn(List.of(sub));
        UserInterests ui = buildInterest("POLITICS");
        when(userInterestsRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(ui));

        when(aiProvider.summarize(any(), any(), eq(SummaryDepth.BALANCED))).thenReturn("summary");

        aiProcessingService.processArticle(article);

        verify(notificationSendService, never()).enqueueBreaking(any(), anyLong());
    }

    // ─────────────────────────────────────────────────────────
    // (3) 카테고리 분류 미완(PENDING) → enqueueBreaking 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("categoryStatus=PENDING → BREAKING 트리거 없음")
    void processArticle_categoryNotCompleted_noEnqueue() {
        Article article = buildArticle(ARTICLE_ID, ProcessingStatus.PENDING, null);

        when(aiProvider.summarize(any(), any(), eq(SummaryDepth.BALANCED))).thenReturn("summary");

        aiProcessingService.processArticle(article);

        verify(topicSubscriptionRepository, never()).findByIdTopic(any());
        verify(notificationSendService, never()).enqueueBreaking(any(), anyLong());
    }
}
