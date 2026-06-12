package com.newscurator.service;

import com.newscurator.domain.Article;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.dto.response.ArticleItem;
import com.newscurator.dto.response.ArticleSearchResponse;
import com.newscurator.dto.response.FeedSummarySlot;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_SIZE = 50;

    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final SavedArticleRepository savedArticleRepository;
    private final ReadingPreferenceRepository readingPreferenceRepository;

    public SearchService(
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            SavedArticleRepository savedArticleRepository,
            ReadingPreferenceRepository readingPreferenceRepository) {
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.savedArticleRepository = savedArticleRepository;
        this.readingPreferenceRepository = readingPreferenceRepository;
    }

    /**
     * FR-013: 검색 결과는 relevance(pg_bigm GREATEST similarity) 정렬만 수행.
     * user_interests / follow_keywords 참조 금지.
     */
    public ArticleSearchResponse search(UUID accountId, String query, String cursor, int size) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || q.length() > 100) {
            throw new IllegalArgumentException("검색어는 2자 이상 100자 이하여야 합니다");
        }

        int pageSize = Math.max(1, Math.min(size, MAX_SIZE));
        int fetchLimit = pageSize + 1;

        SummaryDepth preferredDepth = readingPreferenceRepository.findByAccountId(accountId)
                .map(ReadingPreference::getSummaryDepth)
                .orElse(SummaryDepth.BALANCED);

        SearchCursor searchCursor = decodeCursor(cursor);

        List<Object[]> rows = (searchCursor != null)
                ? articleRepository.searchByQueryWithCursor(
                        q,
                        searchCursor.score(),
                        searchCursor.publishedAt(),
                        searchCursor.articleId(),
                        fetchLimit)
                : articleRepository.searchByQuery(q, fetchLimit);

        boolean hasNext = rows.size() > pageSize;
        List<Object[]> pageRows = rows.subList(0, Math.min(pageSize, rows.size()));

        if (pageRows.isEmpty()) {
            return new ArticleSearchResponse(List.of(), null, false);
        }

        // Extract ordered IDs, scores, source names from native query result
        List<Long> orderedIds = pageRows.stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();

        Map<Long, Double> scoreMap = IntStream.range(0, pageRows.size()).boxed()
                .collect(Collectors.toMap(
                        i -> ((Number) pageRows.get(i)[0]).longValue(),
                        i -> ((Number) pageRows.get(i)[1]).doubleValue(),
                        (a, b) -> a));

        Map<Long, String> sourceNameMap = IntStream.range(0, pageRows.size()).boxed()
                .collect(Collectors.toMap(
                        i -> ((Number) pageRows.get(i)[0]).longValue(),
                        i -> (String) pageRows.get(i)[2],
                        (a, b) -> a));

        // Batch load articles, then restore original order
        Map<Long, Integer> positionMap = IntStream.range(0, orderedIds.size()).boxed()
                .collect(Collectors.toMap(orderedIds::get, i -> i));

        List<Article> articles = new ArrayList<>(articleRepository.findAllById(orderedIds));
        articles.sort(Comparator.comparingInt(a -> positionMap.getOrDefault(a.getId(), Integer.MAX_VALUE)));

        // Batch load summaries and saved status
        Map<Long, List<Summary>> summaryMap = buildSummaryMap(orderedIds);
        Set<Long> savedIds = orderedIds.isEmpty() ? Set.of()
                : savedArticleRepository.findSavedArticleIdsByAccountIdAndArticleIdIn(accountId, orderedIds);

        List<ArticleItem> items = articles.stream()
                .map(a -> toArticleItem(a, scoreMap, sourceNameMap, summaryMap, savedIds, preferredDepth))
                .toList();

        String nextCursor = hasNext ? encodeLastCursor(pageRows.get(pageRows.size() - 1)) : null;

        return new ArticleSearchResponse(items, nextCursor, hasNext);
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

        for (SummaryDepth depth : fallbackOrder(preferred)) {
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

    // ── Item builder ─────────────────────────────────────────────────────────

    private ArticleItem toArticleItem(
            Article a,
            Map<Long, Double> scoreMap,
            Map<Long, String> sourceNameMap,
            Map<Long, List<Summary>> summaryMap,
            Set<Long> savedIds,
            SummaryDepth preferredDepth) {

        String category = a.getCategory() != null ? a.getCategory().name() : "OTHER";
        String sourceName = sourceNameMap.getOrDefault(a.getId(), null);
        List<Summary> summaries = summaryMap.getOrDefault(a.getId(), List.of());
        FeedSummarySlot slot = buildSummarySlot(summaries, preferredDepth);
        Double score = scoreMap.getOrDefault(a.getId(), 0.0);
        boolean saved = savedIds.contains(a.getId());

        return new ArticleItem(a.getId(), a.getTitle(), category, a.getPublishedAt(),
                sourceName, slot, score, saved);
    }

    // ── Cursor codec ─────────────────────────────────────────────────────────

    // row[0]=id, row[1]=relevance_score, row[2]=source_name, row[3]=published_at(Timestamp)
    private String encodeLastCursor(Object[] lastRow) {
        long articleId = ((Number) lastRow[0]).longValue();
        double score = ((Number) lastRow[1]).doubleValue();
        long publishedAtMs = toOffsetDateTime(lastRow[3]).toInstant().toEpochMilli();
        String raw = score + "|" + publishedAtMs + "|" + articleId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // Hibernate 6 maps TIMESTAMPTZ → java.time.Instant in native queries (Spring Boot 4)
    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Cannot convert to OffsetDateTime: " + value.getClass());
    }

    private SearchCursor decodeCursor(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 3) return null;
            double score = Double.parseDouble(parts[0]);
            OffsetDateTime publishedAt = Instant.ofEpochMilli(Long.parseLong(parts[1]))
                    .atOffset(ZoneOffset.UTC);
            long articleId = Long.parseLong(parts[2]);
            return new SearchCursor(score, publishedAt, articleId);
        } catch (Exception e) {
            log.debug("Invalid search cursor, falling back to first page: {}", e.getMessage());
            return null;
        }
    }

    private record SearchCursor(double score, OffsetDateTime publishedAt, long articleId) {}
}
