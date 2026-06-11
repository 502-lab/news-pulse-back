package com.newscurator.service;

import com.newscurator.client.source.ArticleCandidate;
import com.newscurator.client.source.SourceAdapter;
import com.newscurator.config.RetentionProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.ArticleSource;
import com.newscurator.domain.Source;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.SourceDailyUsageRepository;
import com.newscurator.repository.SourceRepository;
import com.newscurator.util.UrlNormalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출처 1개에 대한 수집 트랜잭션 실행자.
 * CollectionService에서 this.collectFromSource() 직접 호출 시 Spring AOP 프록시가
 * 우회되어 @Transactional이 무효화되는 문제를 방지하기 위해 별도 빈으로 분리.
 */
@Service
public class SourceCollectionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SourceCollectionExecutor.class);

    private final ArticleRepository articleRepository;
    private final ArticleSourceRepository articleSourceRepository;
    private final SourceRepository sourceRepository;
    private final SourceDailyUsageRepository sourceUsageRepository;
    private final UrlNormalizer urlNormalizer;
    private final List<SourceAdapter> adapters;
    private final RetentionProperties retentionProperties;

    public SourceCollectionExecutor(
            ArticleRepository articleRepository,
            ArticleSourceRepository articleSourceRepository,
            SourceRepository sourceRepository,
            SourceDailyUsageRepository sourceUsageRepository,
            UrlNormalizer urlNormalizer,
            List<SourceAdapter> adapters,
            RetentionProperties retentionProperties) {
        this.articleRepository = articleRepository;
        this.articleSourceRepository = articleSourceRepository;
        this.sourceRepository = sourceRepository;
        this.sourceUsageRepository = sourceUsageRepository;
        this.urlNormalizer = urlNormalizer;
        this.adapters = adapters;
        this.retentionProperties = retentionProperties;
    }

    @Transactional
    public void collectFromSource(Source source) {
        SourceAdapter adapter = findAdapter(source);
        List<ArticleCandidate> candidates = adapter.fetchCandidates(source);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int collected = 0;

        for (ArticleCandidate candidate : candidates) {
            // call_budget_daily 초과 시 해당 출처 루프 중단 + WARN 로그 (CHK010)
            sourceUsageRepository.upsertCallCount(source.getId(), today);
            int callCount = sourceUsageRepository.selectCallCount(source.getId(), today);
            if (callCount > source.getCallBudgetDaily()) {
                log.warn("[COLLECT] 일일 호출 예산 초과, sourceId={}, budget={}, count={}",
                        source.getId(), source.getCallBudgetDaily(), callCount);
                break;
            }

            processCandidate(candidate, source);
            collected++;
        }

        source.recordCollected();
        sourceRepository.save(source);
        log.info("[COLLECT] 출처 수집 완료, sourceId={}, name={}, collected={}",
                source.getId(), source.getName(), collected);
    }

    private void processCandidate(ArticleCandidate candidate, Source source) {
        if (candidate.url() == null || candidate.url().isBlank()) {
            return;
        }

        String normalizedUrl = urlNormalizer.normalize(candidate.url());
        Optional<Article> existing = articleRepository.findByNormalizedUrl(normalizedUrl);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (existing.isPresent()) {
            Article article = existing.get();
            if (!articleSourceRepository.existsByArticleIdAndSourceId(
                    article.getId(), source.getId())) {
                ArticleSource mergeSource = ArticleSource.builder()
                        .article(article)
                        .source(source)
                        .collectedAt(now)
                        .merge(true)
                        .build();
                articleSourceRepository.save(mergeSource);
            }
        } else {
            OffsetDateTime publishedAt =
                    candidate.publishedAt() != null ? candidate.publishedAt() : now;
            OffsetDateTime expiresAt = now.plusDays(retentionProperties.days());

            Article article = Article.builder()
                    .normalizedUrl(normalizedUrl)
                    .originalUrl(candidate.url())
                    .title(candidate.title())
                    .author(candidate.author())
                    .publishedAt(publishedAt)
                    .firstCollectedAt(now)
                    .expiresAt(expiresAt)
                    .build();

            Article saved = articleRepository.save(article);

            ArticleSource provenance = ArticleSource.builder()
                    .article(saved)
                    .source(source)
                    .collectedAt(now)
                    .merge(false)
                    .build();
            articleSourceRepository.save(provenance);
        }
    }

    private SourceAdapter findAdapter(Source source) {
        return adapters.stream()
                .filter(a -> a.supports(source.getAdapterType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No adapter for: " + source.getAdapterType()));
    }
}
