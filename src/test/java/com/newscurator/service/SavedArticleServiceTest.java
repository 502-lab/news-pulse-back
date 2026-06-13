package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.SavedArticle;
import com.newscurator.dto.response.SavedArticleListResponse;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.exception.SaveLimitExceededException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.SavedArticleRepository;
import com.newscurator.repository.SummaryRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SavedArticleServiceTest {

    @Mock private SavedArticleRepository savedArticleRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;

    private SavedArticleService savedArticleService;

    @BeforeEach
    void setUp() {
        savedArticleService = new SavedArticleService(
                savedArticleRepository, articleRepository,
                summaryRepository, readingPreferenceRepository);
    }

    // ── [SST1] 재저장 멱등: exists=true → false 즉시 반환, count/save 미호출 ────

    @Test
    @DisplayName("[SST1] 재저장 멱등 — exists=true → 200(false), 상한 검사·save 미호출")
    void save_alreadySaved_returnsIdempotentFalse() {
        UUID accountId = UUID.randomUUID();
        Long articleId = 1L;

        when(savedArticleRepository.existsByAccountIdAndArticleId(accountId, articleId))
                .thenReturn(true);

        boolean result = savedArticleService.save(accountId, articleId);

        assertThat(result).isFalse();
        verify(savedArticleRepository, never()).countByAccountId(any());
        verify(savedArticleRepository, never()).save(any());
    }

    // ── [SST2] 1001번째 신규 저장 → SaveLimitExceededException(409) ────────────

    @Test
    @DisplayName("[SST2] 1001번째 신규 저장 → SaveLimitExceededException(409)")
    void save_newArticle_atLimit_throwsSaveLimitExceeded() {
        UUID accountId = UUID.randomUUID();
        Long articleId = 99L;

        when(savedArticleRepository.existsByAccountIdAndArticleId(accountId, articleId))
                .thenReturn(false);
        when(savedArticleRepository.countByAccountId(accountId)).thenReturn(1000L);

        assertThatThrownBy(() -> savedArticleService.save(accountId, articleId))
                .isInstanceOf(SaveLimitExceededException.class);
        verify(savedArticleRepository, never()).save(any());
    }

    // ── [SST3] C2 엣지: count=1000 + 이미 저장된 기사 재저장 → 200(exists 단락) ─

    @Test
    @DisplayName("[SST3] C2 엣지 — count=1000 + exists=true → 200(false), 409 아님")
    void save_alreadySavedAtLimit_returnsIdempotentNotConflict() {
        UUID accountId = UUID.randomUUID();
        Long articleId = 42L;

        when(savedArticleRepository.existsByAccountIdAndArticleId(accountId, articleId))
                .thenReturn(true); // exists → 단락

        boolean result = savedArticleService.save(accountId, articleId);

        assertThat(result).isFalse();
        // count 호출 없음 — exists가 먼저 단락
        verify(savedArticleRepository, never()).countByAccountId(any());
    }

    // ── [SST4] 미존재 articleId 저장 → ArticleNotFoundException(404) ─────────

    @Test
    @DisplayName("[SST4] 미존재 articleId → ArticleNotFoundException(404)")
    void save_articleNotFound_throwsArticleNotFoundException() {
        UUID accountId = UUID.randomUUID();
        Long missingId = 9999L;

        when(savedArticleRepository.existsByAccountIdAndArticleId(accountId, missingId))
                .thenReturn(false);
        when(savedArticleRepository.countByAccountId(accountId)).thenReturn(5L);
        when(articleRepository.existsById(missingId)).thenReturn(false);

        assertThatThrownBy(() -> savedArticleService.save(accountId, missingId))
                .isInstanceOf(ArticleNotFoundException.class);
        verify(savedArticleRepository, never()).save(any());
    }

    // ── [SST5] 미저장 기사 unsave → no-op, 예외 없음 ──────────────────────────

    @Test
    @DisplayName("[SST5] 미저장 기사 unsave → no-op(204), 예외 없음")
    void unsave_notSaved_noOpNoException() {
        UUID accountId = UUID.randomUUID();
        Long articleId = 77L;

        doNothing().when(savedArticleRepository).deleteByAccountIdAndArticleId(accountId, articleId);

        assertThatCode(() -> savedArticleService.unsave(accountId, articleId))
                .doesNotThrowAnyException();
        verify(savedArticleRepository).deleteByAccountIdAndArticleId(accountId, articleId);
    }

    // ── [SST6] list — SavedArticleItem(savedAt) 래퍼로 응답 ───────────────────

    @Test
    @DisplayName("[SST6] list — SavedArticleItem에 savedAt 포함, articles 비어있지 않음")
    void list_returnsSavedArticleItemsWithSavedAt() {
        UUID accountId = UUID.randomUUID();

        Article mockArticle = mock(Article.class);
        when(mockArticle.getId()).thenReturn(1L);
        when(mockArticle.getTitle()).thenReturn("테스트 기사");
        when(mockArticle.getCategory()).thenReturn(null);
        when(mockArticle.getPublishedAt()).thenReturn(OffsetDateTime.now());

        Instant savedAt = Instant.now().minusSeconds(30);
        SavedArticle sa = SavedArticle.builder()
                .accountId(accountId)
                .articleId(1L)
                .build();
        // savedAt은 @PrePersist에서 설정되므로 reflection으로 주입
        try {
            var f = SavedArticle.class.getDeclaredField("savedAt");
            f.setAccessible(true);
            f.set(sa, savedAt);
            var fId = SavedArticle.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(sa, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(savedArticleRepository.findByAccountIdOrderBySavedAtDesc(eq(accountId), any(Pageable.class)))
                .thenReturn(List.of(sa));
        when(articleRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(mockArticle));
        when(summaryRepository.findCompletedByArticleIdIn(anyList()))
                .thenReturn(List.of());
        when(readingPreferenceRepository.findByAccountId(accountId))
                .thenReturn(Optional.empty());

        SavedArticleListResponse response = savedArticleService.list(accountId, null, 20, false, null);

        assertThat(response.articles()).hasSize(1);
        assertThat(response.articles().get(0).savedAt()).isEqualTo(savedAt);
        assertThat(response.articles().get(0).article().id()).isEqualTo(1L);
        assertThat(response.hasNext()).isFalse();
    }

    // ── [SST7] 신규 저장 성공 → true(201) ────────────────────────────────────

    @Test
    @DisplayName("[SST7] 신규 저장 성공 → true(201)")
    void save_newArticle_returnsTrue() {
        UUID accountId = UUID.randomUUID();
        Long articleId = 5L;

        when(savedArticleRepository.existsByAccountIdAndArticleId(accountId, articleId))
                .thenReturn(false);
        when(savedArticleRepository.countByAccountId(accountId)).thenReturn(0L);
        when(articleRepository.existsById(articleId)).thenReturn(true);
        when(savedArticleRepository.save(any())).thenReturn(null);

        boolean result = savedArticleService.save(accountId, articleId);

        assertThat(result).isTrue();
        verify(savedArticleRepository).save(any(SavedArticle.class));
    }
}
