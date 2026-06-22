package com.newscurator.service;

import com.newscurator.client.keyword.KeywordExtractor;
import com.newscurator.config.TrendProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.IssueSnapshot;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.IssueSnapshotRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.TrendKeywordSlotRepository;
import com.newscurator.service.trend.DerivedIssue;
import com.newscurator.service.trend.IssueClusterContext;
import com.newscurator.service.trend.IssueClusterer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트렌드 집계 — 키워드 추출(summary-race 게이팅) → 슬롯 멱등 UPSERT → 이슈 재산출(re-derive, 전량 교체).
 */
@Service
public class TrendAggregationService {

    private static final Logger log = LoggerFactory.getLogger(TrendAggregationService.class);
    private static final int WEEK_DAYS = 7;
    private static final String METHOD_CO_OCCURRENCE = "CO_OCCURRENCE";

    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final ArticleKeywordRepository articleKeywordRepository;
    private final TrendKeywordSlotRepository trendKeywordSlotRepository;
    private final IssueSnapshotRepository issueSnapshotRepository;
    private final KeywordExtractor keywordExtractor;
    private final IssueClusterer issueClusterer;
    private final TrendProperties trendProperties;

    public TrendAggregationService(
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            ArticleKeywordRepository articleKeywordRepository,
            TrendKeywordSlotRepository trendKeywordSlotRepository,
            IssueSnapshotRepository issueSnapshotRepository,
            KeywordExtractor keywordExtractor,
            IssueClusterer issueClusterer,
            TrendProperties trendProperties) {
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.articleKeywordRepository = articleKeywordRepository;
        this.trendKeywordSlotRepository = trendKeywordSlotRepository;
        this.issueSnapshotRepository = issueSnapshotRepository;
        this.keywordExtractor = keywordExtractor;
        this.issueClusterer = issueClusterer;
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

        // 이슈 재산출(re-derive, OI-4): co-occurrence 클러스터링 → issue_snapshot 전량 교체(clean cutover)
        int issueCount = rederiveIssues(now);

        log.info("[TREND] 집계 완료, candidates={}, extractedArticles={}, terms={}, slots={}, issues={}",
                candidates.size(), extractedArticles, insertedTerms, affectedSlots, issueCount);
    }

    /**
     * 이슈 재산출. 최근 24h 기사+키워드로 co-occurrence 클러스터링하고 issue_snapshot을 전량 교체한다.
     * cross-run 안정 ID 없음(OI-4). 단일 TX(aggregate의 @Transactional) 내 TRUNCATE+INSERT = clean cutover.
     */
    private int rederiveIssues(OffsetDateTime now) {
        Instant clusterWindow = now.minusHours(trendProperties.top5WindowHours()).toInstant();

        // article_id → keywords(윈도우)
        Map<Long, List<String>> articleKeywords = new HashMap<>();
        for (Object[] row : articleKeywordRepository.windowArticleKeywords(clusterWindow)) {
            Long articleId = ((Number) row[0]).longValue();
            articleKeywords.computeIfAbsent(articleId, k -> new ArrayList<>()).add((String) row[1]);
        }

        // term → WoW deltaPct (멤버 키워드 delta 집계용)
        Instant curStart = now.minusDays(WEEK_DAYS).toInstant();
        Instant prevStart = now.minusDays(2L * WEEK_DAYS).toInstant();
        Map<String, Double> keywordDelta = new HashMap<>();
        for (Object[] row : trendKeywordSlotRepository.weeklyKeywordCounts(curStart, prevStart)) {
            String term = (String) row[0];
            long cur = ((Number) row[1]).longValue();
            long prev = ((Number) row[2]).longValue();
            keywordDelta.put(term, prev == 0 ? null : 100.0 * (cur - prev) / prev);
        }

        List<DerivedIssue> issues =
                issueClusterer.cluster(new IssueClusterContext(articleKeywords, keywordDelta));

        issueSnapshotRepository.truncate(); // 전량 교체
        List<IssueSnapshot> rows = issues.stream().map(TrendAggregationService::toSnapshot).toList();
        issueSnapshotRepository.saveAll(rows);
        return rows.size();
    }

    private static IssueSnapshot toSnapshot(DerivedIssue issue) {
        BigDecimal delta = issue.delta() == null
                ? null
                : BigDecimal.valueOf(issue.delta()).setScale(2, RoundingMode.HALF_UP);
        return IssueSnapshot.builder()
                .clusteringMethod(METHOD_CO_OCCURRENCE)
                .delta(delta)
                .keywords(issue.keywords().toArray(new String[0]))
                .articleIds(issue.articleIds().toArray(new Long[0]))
                .build();
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
