package com.newscurator.service;

import com.newscurator.client.keyword.KeywordExtractor;
import com.newscurator.config.TrendCacheConfig;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final CacheManager cacheManager;

    public TrendAggregationService(
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            ArticleKeywordRepository articleKeywordRepository,
            TrendKeywordSlotRepository trendKeywordSlotRepository,
            IssueSnapshotRepository issueSnapshotRepository,
            KeywordExtractor keywordExtractor,
            IssueClusterer issueClusterer,
            TrendProperties trendProperties,
            CacheManager cacheManager) {
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.articleKeywordRepository = articleKeywordRepository;
        this.trendKeywordSlotRepository = trendKeywordSlotRepository;
        this.issueSnapshotRepository = issueSnapshotRepository;
        this.keywordExtractor = keywordExtractor;
        this.issueClusterer = issueClusterer;
        this.trendProperties = trendProperties;
        this.cacheManager = cacheManager;
    }

    /**
     * 집계 1회: 추출 + 슬롯 UPSERT (멱등) + 이슈 재산출. 단일 인스턴스 fixedDelay 전제.
     *
     * <p>캐시 무효화는 <b>커밋 후(afterCommit)</b>에만 수행한다(R-006). {@code @CacheEvict}는
     * advisor 순서가 고정되지 않아 evict가 커밋 전에 실행될 수 있고, 그 사이 동시 read가 커밋 전
     * 데이터를 재적재해 최대 1주기 stale이 남을 수 있다. TX 커밋 후 evict로 그 창을 제거한다.
     */
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

        // 캐시 무효화는 커밋 후에만(아래 등록). 롤백 시엔 evict 미발생 → 데이터·캐시 모두 불변(정합).
        evictTrendCachesAfterCommit();

        log.info("[TREND] 집계 완료, candidates={}, extractedArticles={}, terms={}, slots={}, issues={}",
                candidates.size(), extractedArticles, insertedTerms, affectedSlots, issueCount);
    }

    /**
     * 트렌드 read 캐시 무효화를 활성 TX의 커밋 후로 미룬다. aggregate()는 항상 {@code @Transactional}
     * 경계 안에서 외부 호출(스케줄러→프록시)되므로 동기화가 활성 상태다. 방어적으로, 동기화가 비활성인
     * (비정상) 호출에선 즉시 evict로 폴백해 캐시가 절대 stale로 남지 않게 한다.
     */
    private void evictTrendCachesAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearTrendCaches();
                }
            });
        } else {
            clearTrendCaches();
        }
    }

    private void clearTrendCaches() {
        for (String name : TrendCacheConfig.allTrendCaches()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * 보존 정리(FR-009): 90일 경과 트렌드 슬롯 + 잔존 이슈 스냅샷 삭제. 스케줄러 cron에서 호출.
     * 단일 TX. cutoff = now - retentionDays.
     */
    @Transactional
    public void cleanup() {
        Instant cutoff = OffsetDateTime.now().minusDays(trendProperties.retentionDays()).toInstant();
        int slots = trendKeywordSlotRepository.deleteOlderThan(cutoff);
        int issues = issueSnapshotRepository.deleteOlderThan(cutoff);
        log.info("[TREND] 보존 정리 완료, cutoff={}, deletedSlots={}, deletedIssues={}",
                cutoff, slots, issues);
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
