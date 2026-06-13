package com.newscurator.service;

import com.newscurator.config.FeedRankingProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.FollowKeyword;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.Summary;
import com.newscurator.domain.UserInterests;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.dto.response.ArticleItem;
import com.newscurator.dto.response.FeedResponse;
import com.newscurator.dto.response.FeedSummarySlot;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.FollowKeywordRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.UserInterestsRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    private static final int CANDIDATE_FETCH_LIMIT = 500;
    private static final List<ProcessingStatus> VISIBLE_STATUSES =
            List.of(ProcessingStatus.COMPLETED, ProcessingStatus.FAILED);

    private final UserInterestsRepository userInterestsRepository;
    private final FollowKeywordRepository followKeywordRepository;
    private final ReadingPreferenceRepository readingPreferenceRepository;
    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final ArticleSourceRepository articleSourceRepository;
    private final SavedArticleRepository savedArticleRepository;
    private final FeedRankingProperties rankingProps;

    public FeedService(
            UserInterestsRepository userInterestsRepository,
            FollowKeywordRepository followKeywordRepository,
            ReadingPreferenceRepository readingPreferenceRepository,
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            ArticleSourceRepository articleSourceRepository,
            SavedArticleRepository savedArticleRepository,
            FeedRankingProperties rankingProps) {
        this.userInterestsRepository = userInterestsRepository;
        this.followKeywordRepository = followKeywordRepository;
        this.readingPreferenceRepository = readingPreferenceRepository;
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.articleSourceRepository = articleSourceRepository;
        this.savedArticleRepository = savedArticleRepository;
        this.rankingProps = rankingProps;
    }

    public FeedResponse getFeed(UUID accountId, String cursorToken, int requestedSize, Category categoryFilter) {
        int pageSize = Math.max(1, Math.min(requestedSize, 50));

        List<String> userCategories = userInterestsRepository.findByAccountId(accountId).stream()
                .map(UserInterests::getCategory)
                .toList();
        List<String> userKeywords = followKeywordRepository.findByAccountId(accountId).stream()
                .map(FollowKeyword::getKeyword)
                .toList();
        SummaryDepth preferredDepth = readingPreferenceRepository.findByAccountId(accountId)
                .map(ReadingPreference::getSummaryDepth)
                .orElse(SummaryDepth.BALANCED);

        boolean personalized = !userCategories.isEmpty() || !userKeywords.isEmpty();

        FeedCursor cursor = decodeCursor(cursorToken);
        Instant referenceTs = (cursor != null) ? cursor.referenceTs() : Instant.now();

        OffsetDateTime windowStart = referenceTs
                .minus(Duration.ofDays(rankingProps.feedWindowDays()))
                .atOffset(ZoneOffset.UTC);
        OffsetDateTime refTsODT = referenceTs.atOffset(ZoneOffset.UTC);

        List<Article> candidates = fetchCandidates(categoryFilter, windowStart, refTsODT);

        List<ScoredArticle> scored = rankArticles(candidates, userCategories, userKeywords, referenceTs, personalized);

        if (cursor != null) {
            scored = skipToCursor(scored, cursor, personalized);
        }

        boolean hasNext = scored.size() > pageSize;
        List<ScoredArticle> page = scored.subList(0, Math.min(pageSize, scored.size()));

        List<Long> articleIds = page.stream().map(sa -> sa.article().getId()).toList();

        Map<Long, String> sourceNameMap = buildSourceNameMap(articleIds);
        Map<Long, List<Summary>> summaryMap = buildSummaryMap(articleIds);
        Set<Long> savedIds = articleIds.isEmpty() ? Set.of()
                : savedArticleRepository.findSavedArticleIdsByAccountIdAndArticleIdIn(accountId, articleIds);

        List<ArticleItem> items = page.stream()
                .map(sa -> toArticleItem(sa, sourceNameMap, summaryMap, savedIds, preferredDepth))
                .toList();

        String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1), referenceTs) : null;

        return new FeedResponse(items, nextCursor, hasNext, items.size(), personalized);
    }

    /**
     * 브리핑용 피드 후보 — 전체 피드 순위 기준, candidateLimit개 반환.
     * summaryStatus 필터는 호출자(BriefingService) 책임으로 분리.
     */
    @Transactional(readOnly = true)
    public List<Article> getRankedBriefingCandidates(UUID accountId, int candidateLimit) {
        List<String> userCategories = userInterestsRepository.findByAccountId(accountId).stream()
                .map(UserInterests::getCategory).toList();
        List<String> userKeywords = followKeywordRepository.findByAccountId(accountId).stream()
                .map(FollowKeyword::getKeyword).toList();
        boolean personalized = !userCategories.isEmpty() || !userKeywords.isEmpty();

        Instant now = Instant.now();
        OffsetDateTime windowStart = now.minus(Duration.ofDays(rankingProps.feedWindowDays()))
                .atOffset(ZoneOffset.UTC);
        OffsetDateTime refTs = now.atOffset(ZoneOffset.UTC);

        List<Article> candidates = fetchCandidates(null, windowStart, refTs);
        return rankArticles(candidates, userCategories, userKeywords, now, personalized).stream()
                .map(ScoredArticle::article)
                .limit(candidateLimit)
                .toList();
    }

    // ── Ranking ──────────────────────────────────────────────────────────────

    private List<Article> fetchCandidates(Category categoryFilter, OffsetDateTime windowStart, OffsetDateTime refTsODT) {
        PageRequest page = PageRequest.of(0, CANDIDATE_FETCH_LIMIT);
        if (categoryFilter != null) {
            return articleRepository.findFeedCandidatesByCategory(
                    VISIBLE_STATUSES, categoryFilter, windowStart, refTsODT, page);
        }
        return articleRepository.findFeedCandidates(VISIBLE_STATUSES, windowStart, refTsODT, page);
    }

    private List<ScoredArticle> rankArticles(
            List<Article> candidates,
            List<String> userCategories,
            List<String> userKeywords,
            Instant referenceTs,
            boolean personalized) {

        Comparator<ScoredArticle> order;
        if (personalized) {
            order = Comparator.comparingDouble(ScoredArticle::score).reversed()
                    .thenComparing(sa -> sa.article().getPublishedAt(), Comparator.reverseOrder())
                    .thenComparingLong(sa -> -sa.article().getId());
        } else {
            order = Comparator.comparing((ScoredArticle sa) -> sa.article().getPublishedAt())
                    .reversed()
                    .thenComparingLong(sa -> -sa.article().getId());
        }

        return candidates.stream()
                .map(a -> new ScoredArticle(a, computeScore(a, userCategories, userKeywords, referenceTs)))
                .sorted(order)
                .toList();
    }

    double computeScore(Article article, List<String> userCategories, List<String> userKeywords, Instant referenceTs) {
        double score = 0;

        if (article.getCategory() != null && userCategories.contains(article.getCategory().name())) {
            score += rankingProps.categoryMatchScore();
        }

        long matches = userKeywords.stream()
                .filter(kw -> article.getTitle().toLowerCase().contains(kw.toLowerCase()))
                .count();
        score += Math.min(matches * rankingProps.keywordMatchScore(),
                5L * rankingProps.keywordMatchScore());

        long hoursOld = Duration.between(article.getPublishedAt().toInstant(), referenceTs).toHours();
        if (hoursOld >= 0 && hoursOld <= rankingProps.recencyWindowHours()) {
            double ratio = 1.0 - (double) hoursOld / rankingProps.recencyWindowHours();
            score += rankingProps.recencyMaxScore() * ratio;
        }

        return score;
    }

    private List<ScoredArticle> skipToCursor(List<ScoredArticle> sorted, FeedCursor cursor, boolean personalized) {
        int startIndex = 0;
        for (int i = 0; i < sorted.size(); i++) {
            ScoredArticle sa = sorted.get(i);
            Article a = sa.article();
            if (isAtOrBeforeCursor(sa, cursor, personalized)) {
                startIndex = i + 1;
            } else {
                break;
            }
        }
        return sorted.subList(startIndex, sorted.size());
    }

    private boolean isAtOrBeforeCursor(ScoredArticle sa, FeedCursor cursor, boolean personalized) {
        Article a = sa.article();
        if (personalized) {
            // Sort is score DESC: higher score comes first
            int scoreCmp = Double.compare(sa.score(), cursor.rankScore());
            if (scoreCmp > 0) return true;   // sa.score > cursor → sa was earlier in list
            if (scoreCmp < 0) return false;  // sa.score < cursor → sa comes after cursor
        }
        // Sort is publishedAt DESC: newer article comes first
        int timeCmp = a.getPublishedAt().compareTo(cursor.publishedAt());
        if (timeCmp > 0) return true;   // a is newer → came before cursor
        if (timeCmp < 0) return false;  // a is older → comes after cursor
        // Same publishedAt, sort by id DESC: higher id comes first
        return a.getId() >= cursor.articleId();
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    private Map<Long, List<Summary>> buildSummaryMap(List<Long> articleIds) {
        if (articleIds.isEmpty()) return Map.of();
        return summaryRepository.findCompletedByArticleIdIn(articleIds).stream()
                .collect(Collectors.groupingBy(s -> s.getArticle().getId()));
    }

    FeedSummarySlot buildSummarySlot(List<Summary> summaries, SummaryDepth preferred) {
        if (summaries == null || summaries.isEmpty()) {
            return new FeedSummarySlot(null, preferred.name().toLowerCase(), false);
        }
        Map<SummaryDepth, Summary> byDepth = summaries.stream()
                .collect(Collectors.toMap(Summary::getDepth, s -> s, (a, b) -> a));

        SummaryDepth[] order = fallbackOrder(preferred);
        for (SummaryDepth depth : order) {
            Summary s = byDepth.get(depth);
            if (s != null && s.getContent() != null) {
                return new FeedSummarySlot(s.getContent(), depth.name().toLowerCase(), depth != preferred);
            }
        }
        return new FeedSummarySlot(null, preferred.name().toLowerCase(), false);
    }

    private SummaryDepth[] fallbackOrder(SummaryDepth preferred) {
        return switch (preferred) {
            case DEEP -> new SummaryDepth[]{SummaryDepth.DEEP, SummaryDepth.BALANCED, SummaryDepth.BRIEF};
            case BALANCED -> new SummaryDepth[]{SummaryDepth.BALANCED, SummaryDepth.BRIEF, SummaryDepth.DEEP};
            case BRIEF -> new SummaryDepth[]{SummaryDepth.BRIEF, SummaryDepth.BALANCED, SummaryDepth.DEEP};
        };
    }

    // ── Source names ─────────────────────────────────────────────────────────

    private Map<Long, String> buildSourceNameMap(List<Long> articleIds) {
        if (articleIds.isEmpty()) return Map.of();
        return articleSourceRepository.findWithSourceByArticleIdIn(articleIds).stream()
                .collect(Collectors.toMap(
                        as -> as.getArticle().getId(),
                        as -> as.getSource().getName(),
                        (first, second) -> first));
    }

    // ── Item builder ─────────────────────────────────────────────────────────

    private ArticleItem toArticleItem(
            ScoredArticle sa,
            Map<Long, String> sourceNameMap,
            Map<Long, List<Summary>> summaryMap,
            Set<Long> savedIds,
            SummaryDepth preferredDepth) {

        Article a = sa.article();
        String category = a.getCategory() != null ? a.getCategory().name() : "OTHER";
        String sourceName = sourceNameMap.getOrDefault(a.getId(), null);
        List<Summary> summaries = summaryMap.getOrDefault(a.getId(), List.of());
        FeedSummarySlot slot = buildSummarySlot(summaries, preferredDepth);
        boolean saved = savedIds.contains(a.getId());

        return new ArticleItem(a.getId(), a.getTitle(), category, a.getPublishedAt(),
                sourceName, slot, sa.score(), saved);
    }

    // ── Cursor codec ─────────────────────────────────────────────────────────

    private String encodeCursor(ScoredArticle last, Instant referenceTs) {
        Article a = last.article();
        String raw = last.score() + "|" + a.getPublishedAt().toInstant().toEpochMilli()
                + "|" + a.getId() + "|" + referenceTs.toEpochMilli();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private FeedCursor decodeCursor(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 4) return null;
            double rankScore = Double.parseDouble(parts[0]);
            OffsetDateTime publishedAt = Instant.ofEpochMilli(Long.parseLong(parts[1]))
                    .atOffset(ZoneOffset.UTC);
            long articleId = Long.parseLong(parts[2]);
            Instant referenceTs = Instant.ofEpochMilli(Long.parseLong(parts[3]));
            return new FeedCursor(rankScore, publishedAt, articleId, referenceTs);
        } catch (Exception e) {
            log.warn("Invalid feed cursor token: {}", e.getMessage());
            return null;
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record ScoredArticle(Article article, double score) {}

    record FeedCursor(double rankScore, OffsetDateTime publishedAt, long articleId, Instant referenceTs) {}
}
