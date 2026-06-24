package com.newscurator.service;

import com.newscurator.dto.response.ReadCountResponse;
import com.newscurator.dto.response.ReadHistoryItemResponse;
import com.newscurator.dto.response.ReadHistoryListResponse;
import com.newscurator.repository.ArticleEventRepository;
import com.newscurator.repository.ArticleEventRepository.ArticleViewHistoryRow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 009 읽기 추적 조회(US2) — 본인 읽은수(distinct article)·조회 이력(article 최신 1건, 역순).
 *
 * <p>모두 본인 accountId 스코프. 커서는 lastViewedAt(ISO-8601 Instant 문자열).
 */
@Service
public class ReadHistoryService {

    private final ArticleEventRepository articleEventRepository;

    public ReadHistoryService(ArticleEventRepository articleEventRepository) {
        this.articleEventRepository = articleEventRepository;
    }

    /** 읽은수 = 고유 기사 수(distinct article). */
    @Transactional(readOnly = true)
    public ReadCountResponse getReadCount(UUID accountId) {
        return new ReadCountResponse(
                articleEventRepository.countDistinctArticlesByAccount(accountId));
    }

    /** 조회 이력(article 기준 최신 1건, lastViewedAt 역순, 커서 페이지네이션). */
    @Transactional(readOnly = true)
    public ReadHistoryListResponse getHistory(UUID accountId, String cursor, int size) {
        Instant cursorInstant =
                (cursor == null || cursor.isBlank()) ? null : Instant.parse(cursor);
        // hasNext 판정을 위해 size+1 조회
        List<ArticleViewHistoryRow> rows =
                articleEventRepository.findHistory(accountId, cursorInstant, size + 1);

        boolean hasNext = rows.size() > size;
        List<ArticleViewHistoryRow> pageRows = hasNext ? rows.subList(0, size) : rows;

        List<ReadHistoryItemResponse> items =
                pageRows.stream()
                        .map(
                                r ->
                                        new ReadHistoryItemResponse(
                                                r.getArticleId(),
                                                r.getTitle(),
                                                r.getLastViewedAt().atOffset(ZoneOffset.UTC)))
                        .toList();

        String nextCursor =
                hasNext ? pageRows.get(pageRows.size() - 1).getLastViewedAt().toString() : null;
        return new ReadHistoryListResponse(items, nextCursor, hasNext);
    }
}
