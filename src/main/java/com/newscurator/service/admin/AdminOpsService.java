package com.newscurator.service.admin;

import com.newscurator.domain.Article;
import com.newscurator.domain.ExcludedKeyword;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ExcludedKeywordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 수집·콘텐츠 운영 제어(008 US3): 기사 숨김/해제, 제외 키워드 CRUD, 요약 재시도.
 * 모든 변형 액션은 같은 트랜잭션에서 {@link AdminAuditService}로 감사 기록(diff 포함).
 */
@Service
public class AdminOpsService {

    private static final String ACTION_HIDE = "ARTICLE_HIDE";
    private static final String ACTION_UNHIDE = "ARTICLE_UNHIDE";
    private static final String ACTION_KEYWORD_ADD = "EXCLUDED_KEYWORD_ADD";
    private static final String ACTION_KEYWORD_REMOVE = "EXCLUDED_KEYWORD_REMOVE";
    private static final String ACTION_SUMMARY_RETRY = "SUMMARY_RETRY";

    private final ArticleRepository articleRepository;
    private final ExcludedKeywordRepository excludedKeywordRepository;
    private final AdminAuditService auditService;

    public AdminOpsService(
            ArticleRepository articleRepository,
            ExcludedKeywordRepository excludedKeywordRepository,
            AdminAuditService auditService) {
        this.articleRepository = articleRepository;
        this.excludedKeywordRepository = excludedKeywordRepository;
        this.auditService = auditService;
    }

    // ── 기사 숨김/해제 ──

    @Transactional
    public void hideArticle(UUID actorId, Long articleId) {
        Article a = loadArticle(articleId);
        if (a.isAdminHidden()) {
            return; // 이미 숨김 — no-op
        }
        a.hideByAdmin(OffsetDateTime.now());
        articleRepository.save(a);
        auditService.record(
                actorId, ACTION_HIDE, AuditTargetType.ARTICLE, articleId.toString(),
                Map.of("hidden", true));
    }

    @Transactional
    public void unhideArticle(UUID actorId, Long articleId) {
        Article a = loadArticle(articleId);
        if (!a.isAdminHidden()) {
            return;
        }
        a.unhideByAdmin();
        articleRepository.save(a);
        auditService.record(
                actorId, ACTION_UNHIDE, AuditTargetType.ARTICLE, articleId.toString(),
                Map.of("hidden", false));
    }

    // ── 제외 키워드 CRUD ──

    @Transactional(readOnly = true)
    public List<ExcludedKeyword> listExcludedKeywords() {
        return excludedKeywordRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public ExcludedKeyword addExcludedKeyword(UUID actorId, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("키워드는 비어 있을 수 없습니다");
        }
        if (excludedKeywordRepository.existsByKeyword(normalized)) {
            throw new IllegalArgumentException("이미 등록된 키워드입니다: " + normalized);
        }
        ExcludedKeyword saved =
                excludedKeywordRepository.save(
                        ExcludedKeyword.builder().keyword(normalized).createdBy(actorId).build());
        auditService.record(
                actorId, ACTION_KEYWORD_ADD, AuditTargetType.EXCLUDED_KEYWORD,
                saved.getId().toString(), Map.of("keyword", normalized));
        return saved;
    }

    @Transactional
    public void removeExcludedKeyword(UUID actorId, Long id) {
        ExcludedKeyword kw =
                excludedKeywordRepository
                        .findById(id)
                        .orElseThrow(() -> new AdminTargetNotFoundException("제외 키워드를 찾을 수 없습니다: " + id));
        excludedKeywordRepository.delete(kw);
        auditService.record(
                actorId, ACTION_KEYWORD_REMOVE, AuditTargetType.EXCLUDED_KEYWORD,
                id.toString(), Map.of("keyword", kw.getKeyword()));
    }

    // ── 요약 재시도 ──

    @Transactional
    public void retrySummary(UUID actorId, Long articleId) {
        Article a = loadArticle(articleId);
        String before = a.getSummaryStatus().name();
        a.resetSummaryForRetry(); // FAILED 등 → PENDING 재처리 큐
        articleRepository.save(a);
        auditService.record(
                actorId, ACTION_SUMMARY_RETRY, AuditTargetType.SUMMARY, articleId.toString(),
                Map.of("before", before, "after", a.getSummaryStatus().name()));
    }

    private Article loadArticle(Long articleId) {
        return articleRepository
                .findById(articleId)
                .orElseThrow(() -> new AdminTargetNotFoundException("기사를 찾을 수 없습니다: " + articleId));
    }
}
