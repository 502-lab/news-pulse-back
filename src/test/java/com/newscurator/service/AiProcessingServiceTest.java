package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.exception.AiProviderException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
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
class AiProcessingServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private com.newscurator.service.NotificationSendService notificationSendService;
    @Mock private com.newscurator.repository.TopicSubscriptionRepository topicSubscriptionRepository;
    @Mock private com.newscurator.repository.UserInterestsRepository userInterestsRepository;

    private SummaryService summaryService;
    private AiProcessingService aiProcessingService;

    @BeforeEach
    void setUp() {
        AiProperties.DeepRetryProperties deepRetry = new AiProperties.DeepRetryProperties(60, 5);
        AiProperties aiProperties = new AiProperties(10, 3, 0L, deepRetry);
        summaryService = new SummaryService(aiProperties);
        aiProcessingService =
                new AiProcessingService(
                        aiProvider,
                        articleRepository,
                        summaryRepository,
                        summaryService,
                        aiProperties,
                        notificationSendService,
                        topicSubscriptionRepository,
                        userInterestsRepository);
        // summaryRepository.save returns the saved entity (default mock returns null)
        when(summaryRepository.save(any(Summary.class))).thenAnswer(inv -> inv.getArgument(0));
        when(summaryRepository.findByArticleIdAndDepth(anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("PENDING→COMPLETED 정상 전이: 분류 성공 + BALANCED 요약 생성")
    void processArticle_success_completedTransition() {
        Article article = buildPendingArticle(1L, "경제 뉴스 제목");
        when(aiProvider.classify(anyString(), anyString())).thenReturn(Category.ECONOMY_FINANCE);
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.BALANCED)))
                .thenReturn("경제 뉴스 균형 요약");

        aiProcessingService.processArticle(article);

        verify(article).completeCategory(Category.ECONOMY_FINANCE);
        verify(article).completeSummary();
    }

    @Test
    @DisplayName("분류 실패 시 incrementCategoryRetry 호출 (CHK013)")
    void processArticle_classifyFails_incrementsRetry() {
        Article article = buildPendingArticle(2L, "분류 불가 제목");
        when(aiProvider.classify(anyString(), anyString()))
                .thenThrow(new AiProviderException("API error"));

        aiProcessingService.processArticle(article);

        verify(article).incrementCategoryRetry();
        verify(article, never()).completeCategory(any());
    }

    @Test
    @DisplayName("BRIEF 200자 트런케이션 검증 (CHK014)")
    void processArticle_briefTruncatedTo200Chars() {
        Article article = buildPendingArticle(3L, "제목");
        String longBalanced = "가".repeat(300);
        when(aiProvider.classify(anyString(), anyString())).thenReturn(Category.IT);
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.BALANCED)))
                .thenReturn(longBalanced);

        aiProcessingService.processArticle(article);

        // BRIEF 슬롯이 저장될 때 200자 이하
        verify(summaryRepository, atLeastOnce())
                .save(
                        argThat(
                                s ->
                                        s.getDepth() != SummaryDepth.BRIEF
                                                || (s.getContent() != null
                                                        && s.getContent().length() <= 200)));
    }

    @Test
    @DisplayName("summary_status 독립 관리: 요약 실패 시 incrementSummaryRetry 호출 (CHK002)")
    void processArticle_summaryFails_incrementsSummaryRetry() {
        Article article = buildPendingArticle(4L, "제목");
        when(aiProvider.classify(anyString(), anyString())).thenReturn(Category.POLITICS);
        when(aiProvider.summarize(anyString(), anyString(), eq(SummaryDepth.BALANCED)))
                .thenThrow(new AiProviderException("Summary failed"));

        aiProcessingService.processArticle(article);

        verify(article).completeCategory(Category.POLITICS);
        verify(article, atLeastOnce()).incrementSummaryRetry();
    }

    @Test
    @DisplayName("retry_limit 초과 시 failCategory 호출")
    void processArticle_retryLimitExceeded_permanentFailed() {
        Article article = buildPendingArticle(5L, "제목");
        when(article.getCategoryRetryCount()).thenReturn(3);
        when(aiProvider.classify(anyString(), anyString()))
                .thenThrow(new AiProviderException("API error"));

        aiProcessingService.processArticle(article);

        verify(article).failCategory();
    }

    private Article buildPendingArticle(Long id, String title) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(id);
        when(article.getTitle()).thenReturn(title);
        when(article.getCategoryStatus()).thenReturn(ProcessingStatus.PENDING);
        when(article.getSummaryStatus()).thenReturn(ProcessingStatus.PENDING);
        when(article.getCategoryRetryCount()).thenReturn(0);
        when(article.getSummaryRetryCount()).thenReturn(0);
        when(article.getFirstCollectedAt()).thenReturn(OffsetDateTime.now());
        return article;
    }
}
