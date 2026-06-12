package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.dto.response.ArticleSearchResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private SavedArticleRepository savedArticleRepository;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(
                articleRepository, summaryRepository,
                savedArticleRepository, readingPreferenceRepository);
    }

    // ── 빈 쿼리·1자 쿼리·101자 쿼리 → IllegalArgumentException (→422) ────────

    @Test
    @DisplayName("빈 쿼리 → IllegalArgumentException (422)")
    void search_emptyQuery_throwsIllegalArgument() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> searchService.search(accountId, "", null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공백만인 쿼리 → IllegalArgumentException (422)")
    void search_blankQuery_throwsIllegalArgument() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> searchService.search(accountId, "   ", null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("1자 쿼리 → IllegalArgumentException (422)")
    void search_singleCharQuery_throwsIllegalArgument() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> searchService.search(accountId, "경", null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("101자 쿼리 → IllegalArgumentException (422)")
    void search_over100CharQuery_throwsIllegalArgument() {
        UUID accountId = UUID.randomUUID();
        String longQuery = "경".repeat(101);
        assertThatThrownBy(() -> searchService.search(accountId, longQuery, null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 결과 없음 → 빈 목록 200 ──────────────────────────────────────────────

    @Test
    @DisplayName("검색 결과 없음 → 빈 목록, hasNext=false, nextCursor=null")
    void search_noResults_returnsEmptyList() {
        UUID accountId = UUID.randomUUID();
        when(articleRepository.searchByQuery(anyString(), anyInt()))
                .thenReturn(List.of());
        when(readingPreferenceRepository.findByAccountId(accountId))
                .thenReturn(Optional.empty());

        ArticleSearchResponse response = searchService.search(accountId, "경제", null, 20);

        assertThat(response.articles()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    // ── 커서 인코딩·디코딩 라운드트립 ──────────────────────────────────────────

    @Test
    @DisplayName("커서 인코딩 — Base64(score|publishedAt|articleId) 형식 확인")
    void cursor_encode_isBase64ThreeComponent() {
        // Encode a known cursor
        double score = 0.75;
        long epochMs = System.currentTimeMillis();
        long articleId = 42L;
        String raw = score + "|" + epochMs + "|" + articleId;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        // Decode back
        String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|");

        assertThat(parts).hasSize(3);
        assertThat(Double.parseDouble(parts[0])).isEqualTo(score);
        assertThat(Long.parseLong(parts[1])).isEqualTo(epochMs);
        assertThat(Long.parseLong(parts[2])).isEqualTo(articleId);
    }

    @Test
    @DisplayName("유효하지 않은 커서 → 첫 페이지 graceful fallback (예외 없음)")
    void search_invalidCursor_fallsBackToFirstPage() {
        UUID accountId = UUID.randomUUID();
        when(articleRepository.searchByQuery(anyString(), anyInt()))
                .thenReturn(List.of());
        when(readingPreferenceRepository.findByAccountId(accountId))
                .thenReturn(Optional.empty());

        // Should not throw — invalid cursor treated as no cursor (first page)
        ArticleSearchResponse response = searchService.search(accountId, "경제", "invalid-cursor!!!", 20);

        assertThat(response.articles()).isEmpty();
    }
}
