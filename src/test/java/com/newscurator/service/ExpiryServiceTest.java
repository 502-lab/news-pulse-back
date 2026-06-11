package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newscurator.config.RetentionProperties;
import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpiryServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    private ExpiryService expiryService;

    @BeforeEach
    void setUp() {
        RetentionProperties retention = new RetentionProperties(90, 7);
        expiryService = new ExpiryService(articleRepository, retention);
    }

    @Test
    @DisplayName("1단계: expires_at 초과 + user_saved=false → feed_visible=false")
    void hideExpiredArticles_setsInvisible() {
        when(articleRepository.hideExpiredArticles(any())).thenReturn(5);

        expiryService.hideExpiredArticles();

        verify(articleRepository).hideExpiredArticles(any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("2단계: grace period 경과 + user_saved=false → 물리 삭제")
    void deleteGracePeriodExpired_deletesArticles() {
        Article expiredArticle = mock(Article.class);
        when(articleRepository.findArticlesToDelete(any())).thenReturn(List.of(expiredArticle));

        expiryService.deleteGracePeriodExpired();

        verify(articleRepository).delete(expiredArticle);
    }

    @Test
    @DisplayName("삭제 대상이 없으면 delete 호출 없음")
    void deleteGracePeriodExpired_noArticles_noDelete() {
        when(articleRepository.findArticlesToDelete(any())).thenReturn(List.of());

        expiryService.deleteGracePeriodExpired();

        verify(articleRepository, never()).delete(any());
    }
}
