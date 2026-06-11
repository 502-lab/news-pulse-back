package com.newscurator.service;

import com.newscurator.config.RetentionProperties;
import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final ArticleRepository articleRepository;
    private final RetentionProperties retentionProperties;

    public ExpiryService(ArticleRepository articleRepository, RetentionProperties retentionProperties) {
        this.articleRepository = articleRepository;
        this.retentionProperties = retentionProperties;
    }

    /**
     * 1단계 만료: expires_at < NOW() AND user_saved=false → feed_visible=false.
     * (FR-018)
     */
    @Transactional
    public void hideExpiredArticles() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int hidden = articleRepository.hideExpiredArticles(now);
        log.info("[EXPIRY] 1단계 만료 처리, hidden={}", hidden);
    }

    /**
     * 2단계 만료: feed_visible=false AND updated_at < grace_cutoff AND user_saved=false → 물리 삭제.
     * (FR-019)
     */
    @Transactional
    public void deleteGracePeriodExpired() {
        OffsetDateTime graceCutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(retentionProperties.gracePeriodDays());
        List<Article> toDelete = articleRepository.findArticlesToDelete(graceCutoff);

        for (Article article : toDelete) {
            articleRepository.delete(article);
        }
        log.info("[EXPIRY] 2단계 물리 삭제, count={}", toDelete.size());
    }
}
