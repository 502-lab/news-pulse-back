package com.newscurator.service;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.AiTransientException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);

    private final AiProvider aiProvider;
    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryService summaryService;
    private final AiProperties aiProperties;

    public AiProcessingService(
            AiProvider aiProvider,
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            SummaryService summaryService,
            AiProperties aiProperties) {
        this.aiProvider = aiProvider;
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.summaryService = summaryService;
        this.aiProperties = aiProperties;
    }

    /**
     * PENDING 배치 조회 후 분류·요약 처리.
     * SELECT ... FOR UPDATE SKIP LOCKED 로 다중 인스턴스 safe (research #13).
     */
    @Transactional
    public void processBatch() {
        List<Article> batch = articleRepository.lockAndClaimPending(aiProperties.batchSize());
        log.info("[AI] 배치 처리 시작, size={}", batch.size());
        for (Article article : batch) {
            try {
                processArticle(article);
            } catch (AiTransientException e) {
                // 일시적 오류 (429/5xx/타임아웃): 배치 조기 중단, 다음 스케줄 사이클에 재시도
                log.warn("[AI] 일시적 오류로 배치 조기 중단 (다음 사이클에 재시도), articleId={}: {}",
                        article.getId(), e.getMessage());
                break;
            }
        }
    }

    @Transactional
    public void processArticle(Article article) {
        processCategory(article);
        processSummary(article);
        articleRepository.save(article);
    }

    private void processCategory(Article article) {
        if (article.getCategoryStatus() != ProcessingStatus.PENDING) {
            return;
        }

        try {
            Category category = aiProvider.classify(article.getTitle(), "");
            article.completeCategory(category);
        } catch (AiTransientException e) {
            throw e;
        } catch (AiProviderException e) {
            article.incrementCategoryRetry();
            if (article.getCategoryRetryCount() >= aiProperties.retryLimit()) {
                log.warn("[AI] 카테고리 분류 영구 실패, articleId={}", article.getId());
                article.failCategory();
            } else {
                log.warn("[AI] 카테고리 분류 실패, 재시도 예정, articleId={}, retry={}",
                        article.getId(), article.getCategoryRetryCount());
            }
        }
    }

    private void processSummary(Article article) {
        if (article.getSummaryStatus() != ProcessingStatus.PENDING) {
            return;
        }

        try {
            // BALANCED 슬롯 eager 생성
            // article.summary_status = COMPLETED 는 balanced 슬롯 완료를 의미 (CHK002)
            String balancedContent = aiProvider.summarize(
                    article.getTitle(), "", SummaryDepth.BALANCED);

            Summary balancedSlot = getOrCreateSlot(article, SummaryDepth.BALANCED);
            balancedSlot.markPending();
            balancedSlot.complete(balancedContent);
            summaryRepository.save(balancedSlot);

            // BRIEF 슬롯: balanced 트런케이션으로 즉시 생성 (CHK014, 별도 AI 호출 없음)
            String briefContent = summaryService.truncateForBrief(balancedContent);
            Summary briefSlot = getOrCreateSlot(article, SummaryDepth.BRIEF);
            briefSlot.completeWithoutAi(briefContent);
            summaryRepository.save(briefSlot);

            // DEEP 슬롯: NOT_GENERATED 초기화 (lazy; 최초 상세 조회 시 생성)
            Summary deepSlot = getOrCreateSlot(article, SummaryDepth.DEEP);
            summaryRepository.save(deepSlot);

            article.completeSummary();

        } catch (AiTransientException e) {
            throw e;
        } catch (AiProviderException e) {
            article.incrementSummaryRetry();
            if (article.getSummaryRetryCount() >= aiProperties.retryLimit()) {
                log.warn("[AI] 요약 생성 영구 실패, articleId={}", article.getId());
                article.failSummary();
            } else {
                log.warn("[AI] 요약 생성 실패, 재시도 예정, articleId={}, retry={}",
                        article.getId(), article.getSummaryRetryCount());
            }
        }
    }

    private Summary getOrCreateSlot(Article article, SummaryDepth depth) {
        return summaryRepository
                .findByArticleIdAndDepth(article.getId(), depth)
                .orElseGet(
                        () -> {
                            Summary slot = Summary.builder()
                                    .article(article)
                                    .depth(depth)
                                    .build();
                            return summaryRepository.save(slot);
                        });
    }
}
