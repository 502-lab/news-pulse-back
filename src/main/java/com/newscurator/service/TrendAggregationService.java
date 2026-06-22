package com.newscurator.service;

import com.newscurator.client.keyword.KeywordExtractor;
import com.newscurator.config.TrendProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.TrendKeywordSlotRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트렌드 집계 — 키워드 추출(summary-race 게이팅) → 슬롯 멱등 UPSERT.
 * 이슈 재산출(US5)은 별도 단계로 추후 연결(T038).
 */
@Service
public class TrendAggregationService {

    private static final Logger log = LoggerFactory.getLogger(TrendAggregationService.class);

    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final ArticleKeywordRepository articleKeywordRepository;
    private final TrendKeywordSlotRepository trendKeywordSlotRepository;
    private final KeywordExtractor keywordExtractor;
    private final TrendProperties trendProperties;

    public TrendAggregationService(
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            ArticleKeywordRepository articleKeywordRepository,
            TrendKeywordSlotRepository trendKeywordSlotRepository,
            KeywordExtractor keywordExtractor,
            TrendProperties trendProperties) {
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.articleKeywordRepository = articleKeywordRepository;
        this.trendKeywordSlotRepository = trendKeywordSlotRepository;
        this.keywordExtractor = keywordExtractor;
        this.trendProperties = trendProperties;
    }

    /** 집계 1회: 추출 + 슬롯 UPSERT (멱등). 단일 인스턴스 fixedDelay 전제. */
    @Transactional
    public void aggregate() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowStart = now.minusHours(trendProperties.extractWindowHours());
        OffsetDateTime summaryCutoff = now.minusHours(trendProperties.summaryWaitHours());

        List<Article> candidates =
                articleRepository.findTrendExtractionCandidates(windowStart, summaryCutoff);

        int extractedArticles = 0;
        int insertedTerms = 0;
        for (Article a : candidates) {
            String text = buildText(a);
            Set<String> terms = keywordExtractor.extractNouns(text);
            for (String term : terms) {
                articleKeywordRepository.insertIgnore(a.getId(), term);
                insertedTerms++;
            }
            extractedArticles++;
        }

        int affectedSlots = trendKeywordSlotRepository.upsertSlots(windowStart.toInstant());

        log.info("[TREND] 집계 완료, candidates={}, extractedArticles={}, terms={}, slots={}",
                candidates.size(), extractedArticles, insertedTerms, affectedSlots);
    }

    /**
     * 추출 본문 구성. COMPLETED + BALANCED 행 present + content non-null·non-blank이면 제목+요약,
     * 그 외(FAILED·1h경과 PENDING·요약부재·null·blank)는 제목만(NPE/null 방어).
     */
    private String buildText(Article a) {
        boolean useSummary = false;
        String content = null;
        if (a.getSummaryStatus() == ProcessingStatus.COMPLETED) {
            Optional<Summary> bal =
                    summaryRepository.findByArticleIdAndDepth(a.getId(), SummaryDepth.BALANCED);
            if (bal.isPresent() && bal.get().getContent() != null && !bal.get().getContent().isBlank()) {
                useSummary = true;
                content = bal.get().getContent();
            }
        }
        return useSummary ? a.getTitle() + " " + content : a.getTitle();
    }
}
