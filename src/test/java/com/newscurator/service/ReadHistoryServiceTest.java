package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newscurator.dto.response.ReadHistoryListResponse;
import com.newscurator.repository.ArticleEventRepository;
import com.newscurator.repository.ArticleEventRepository.ArticleViewHistoryRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 009 T017 — ReadHistoryService 단위: 읽은수 distinct 위임 + 이력 매핑·hasNext·nextCursor. */
@ExtendWith(MockitoExtension.class)
class ReadHistoryServiceTest {

    @Mock private ArticleEventRepository repository;
    @InjectMocks private ReadHistoryService service;

    private final UUID account = UUID.randomUUID();

    @Test
    void getReadCount_delegatesDistinct() {
        when(repository.countDistinctArticlesByAccount(account)).thenReturn(7L);
        assertThat(service.getReadCount(account).readCount()).isEqualTo(7L);
    }

    @Test
    void getHistory_hasNext_setsNextCursor() {
        Instant t1 = Instant.parse("2026-06-24T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-24T09:00:00Z");
        ArticleViewHistoryRow r1 = row(11L, "A", t1);
        ArticleViewHistoryRow r2 = row(12L, "B", t2);
        // size=1 요청 → 서비스는 size+1=2건 조회, hasNext=true, 1건만 반환
        when(repository.findHistory(account, null, 2)).thenReturn(List.of(r1, r2));

        ReadHistoryListResponse resp = service.getHistory(account, null, 1);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).articleId()).isEqualTo(11L);
        assertThat(resp.hasNext()).isTrue();
        assertThat(resp.nextCursor()).isEqualTo(t1.toString());
    }

    @Test
    void getHistory_lastPage_noCursor() {
        ArticleViewHistoryRow r1 = row(11L, "A", Instant.parse("2026-06-24T10:00:00Z"));
        when(repository.findHistory(account, null, 3)).thenReturn(List.of(r1));

        ReadHistoryListResponse resp = service.getHistory(account, null, 2);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.hasNext()).isFalse();
        assertThat(resp.nextCursor()).isNull();
    }

    private ArticleViewHistoryRow row(Long articleId, String title, Instant lastViewedAt) {
        ArticleViewHistoryRow r = mock(ArticleViewHistoryRow.class);
        // truncate로 버려지는 행(hasNext 판정용 +1건)은 일부 getter가 미사용 → lenient
        lenient().when(r.getArticleId()).thenReturn(articleId);
        lenient().when(r.getTitle()).thenReturn(title);
        lenient().when(r.getLastViewedAt()).thenReturn(lastViewedAt);
        return r;
    }
}
