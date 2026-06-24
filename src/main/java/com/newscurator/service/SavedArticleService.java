package com.newscurator.service;

import com.newscurator.domain.Article;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.SavedArticle;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.dto.response.ArticleItem;
import com.newscurator.dto.response.FeedSummarySlot;
import com.newscurator.dto.response.SavedArticleItem;
import com.newscurator.dto.response.SavedArticleListResponse;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.exception.SaveLimitExceededException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedArticleService {

    private static final int MAX_SAVED = 1000;
    private static final int MAX_SIZE = 50;

    private final SavedArticleRepository savedArticleRepository;
    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final ReadingPreferenceRepository readingPreferenceRepository;

    public SavedArticleService(
            SavedArticleRepository savedArticleRepository,
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            ReadingPreferenceRepository readingPreferenceRepository) {
        this.savedArticleRepository = savedArticleRepository;
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.readingPreferenceRepository = readingPreferenceRepository;
    }

    /**
     * 저장 순서:
     * ① existsByAccountIdAndArticleId 먼저 → 있으면 상한 검사 없이 멱등 200 즉시 반환(false)
     * ② 없으면 countByAccountId >= 1000 → SaveLimitExceededException(409)
     * ③ 기사 존재 확인 → ArticleNotFoundException(404)
     * ④ 신규 저장 → true(201)
     */
    @Transactional
    public boolean save(UUID accountId, Long articleId) {
        if (savedArticleRepository.existsByAccountIdAndArticleId(accountId, articleId)) {
            return false; // 200 idempotent
        }
        if (savedArticleRepository.countByAccountId(accountId) >= MAX_SAVED) {
            throw new SaveLimitExceededException();
        }
        if (!articleRepository.existsById(articleId)) {
            throw new ArticleNotFoundException(articleId);
        }
        savedArticleRepository.save(
                SavedArticle.builder().accountId(accountId).articleId(articleId).build());
        return true; // 201
    }

    @Transactional
    public void unsave(UUID accountId, Long articleId) {
        // no-op if not saved (Spring Data JPA DELETE: 0 rows affected = no exception)
        savedArticleRepository.deleteByAccountIdAndArticleId(accountId, articleId);
    }

    @Transactional(readOnly = true)
    public SavedArticleListResponse list(
            UUID accountId, String cursor, int size, boolean listenable, String voiceId) {
        int pageSize = Math.max(1, Math.min(size, MAX_SIZE));
        PageRequest pageable = PageRequest.of(0, pageSize + 1);

        SaveCursor sc = decodeCursor(cursor);
        List<SavedArticle> rows;
        if (listenable) {
            rows = (sc != null)
                    ? savedArticleRepository.findListenableWithCursor(
                            accountId, sc.savedAt(), sc.id(), voiceId, pageable)
                    : savedArticleRepository.findListenableOrderBySavedAtDesc(
                            accountId, voiceId, pageable);
        } else {
            rows = (sc != null)
                    ? savedArticleRepository.findByAccountIdWithCursor(
                            accountId, sc.savedAt(), sc.id(), pageable)
                    : savedArticleRepository.findByAccountIdOrderBySavedAtDesc(accountId, pageable);
        }

        boolean hasNext = rows.size() > pageSize;
        List<SavedArticle> page = rows.subList(0, Math.min(pageSize, rows.size()));

        if (page.isEmpty()) {
            return new SavedArticleListResponse(List.of(), null, false);
        }

        List<Long> articleIds = page.stream().map(SavedArticle::getArticleId).toList();

        Map<Long, Article> articleMap = articleRepository.findAllById(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, a -> a));

        SummaryDepth preferredDepth = readingPreferenceRepository.findByAccountId(accountId)
                .map(ReadingPreference::getSummaryDepth)
                .orElse(SummaryDepth.BALANCED);

        Map<Long, List<Summary>> summaryMap = summaryRepository.findCompletedByArticleIdIn(articleIds).stream()
                .collect(Collectors.groupingBy(s -> s.getArticle().getId()));

        List<SavedArticleItem> items = page.stream()
                .map(sa -> {
                    Article a = articleMap.get(sa.getArticleId());
                    // 008 #13: 관리자 숨김(admin_hidden_at) 기사는 북마크 목록에서 제외(저장 행은 보존)
                    if (a == null || a.isAdminHidden()) return null;
                    String category = a.getCategory() != null ? a.getCategory().name() : "OTHER";
                    FeedSummarySlot slot = buildSummarySlot(
                            summaryMap.getOrDefault(sa.getArticleId(), List.of()), preferredDepth);
                    ArticleItem articleItem = new ArticleItem(
                            a.getId(), a.getTitle(), category, a.getPublishedAt(),
                            null, slot, null, true);
                    return new SavedArticleItem(sa.getSavedAt(), articleItem);
                })
                .filter(Objects::nonNull)
                .toList();

        String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
        return new SavedArticleListResponse(items, nextCursor, hasNext);
    }

    // ── Summary slot ─────────────────────────────────────────────────────────

    private FeedSummarySlot buildSummarySlot(List<Summary> summaries, SummaryDepth preferred) {
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

    // ── Cursor codec ─────────────────────────────────────────────────────────

    private String encodeCursor(SavedArticle last) {
        String raw = last.getSavedAt().toEpochMilli() + "|" + last.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private SaveCursor decodeCursor(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 2) return null;
            Instant savedAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            long id = Long.parseLong(parts[1]);
            return new SaveCursor(savedAt, id);
        } catch (Exception e) {
            return null;
        }
    }

    private record SaveCursor(Instant savedAt, long id) {}
}
