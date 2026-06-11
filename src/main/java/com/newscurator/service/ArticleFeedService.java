package com.newscurator.service;

import com.newscurator.config.FeedProperties;
import com.newscurator.domain.Article;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.request.FeedRequest;
import com.newscurator.dto.response.ArticleFeedItem;
import com.newscurator.dto.response.ArticleFeedResponse;
import com.newscurator.repository.ArticleRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleFeedService {

    private static final List<ProcessingStatus> FEED_STATUSES =
            List.of(ProcessingStatus.COMPLETED, ProcessingStatus.FAILED);

    private final ArticleRepository articleRepository;
    private final FeedProperties feedProperties;

    public ArticleFeedService(ArticleRepository articleRepository, FeedProperties feedProperties) {
        this.articleRepository = articleRepository;
        this.feedProperties = feedProperties;
    }

    @Transactional(readOnly = true)
    public ArticleFeedResponse getFeed(FeedRequest request) {
        int size = clampSize(request.size());
        // size+1 조회로 hasMore 판단
        PageRequest pageable = PageRequest.of(0, size + 1);

        List<Article> articles;
        if (request.cursor() == null || request.cursor().isBlank()) {
            // 첫 페이지
            articles = request.category() == null
                    ? articleRepository.findFeedPage(FEED_STATUSES, pageable)
                    : articleRepository.findFeedPageByCategory(
                            FEED_STATUSES, request.category(), pageable);
        } else {
            // 커서 기반 페이지
            CursorData cursor = decodeCursor(request.cursor());
            articles = request.category() == null
                    ? articleRepository.findFeedPageWithCursor(
                            FEED_STATUSES, cursor.publishedAt(), cursor.id(), pageable)
                    : articleRepository.findFeedPageByCategoryWithCursor(
                            FEED_STATUSES, request.category(), cursor.publishedAt(), cursor.id(), pageable);
        }

        boolean hasMore = articles.size() > size;
        List<Article> page = hasMore ? articles.subList(0, size) : articles;

        List<ArticleFeedItem> items = page.stream().map(this::toFeedItem).toList();
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;

        return new ArticleFeedResponse(items, nextCursor, hasMore, items.size());
    }

    private int clampSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return feedProperties.defaultPageSize();
        }
        return Math.min(requested, feedProperties.maxPageSize());
    }

    private ArticleFeedItem toFeedItem(Article article) {
        // FAILED → OTHER 매핑
        String category = article.getCategoryStatus() == ProcessingStatus.FAILED
                ? "OTHER"
                : (article.getCategory() != null ? article.getCategory().name() : "OTHER");

        return new ArticleFeedItem(
                article.getId(),
                article.getTitle(),
                article.getAuthor(),
                category,
                article.getPublishedAt(),
                article.getFirstCollectedAt(),
                null); // briefSummary: 피드 목록에서는 null, 상세 조회에서 로드
    }

    private String encodeCursor(Article article) {
        String raw = article.getPublishedAt().toString() + "," + article.getId();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private CursorData decodeCursor(String cursor) {
        try {
            String raw = new String(
                    Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
            int comma = raw.lastIndexOf(',');
            OffsetDateTime publishedAt = OffsetDateTime.parse(raw.substring(0, comma));
            Long id = Long.parseLong(raw.substring(comma + 1));
            return new CursorData(publishedAt, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
    }

    private record CursorData(OffsetDateTime publishedAt, Long id) {}
}
