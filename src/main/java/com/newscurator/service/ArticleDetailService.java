package com.newscurator.service;

import com.newscurator.client.ai.AiProvider;
import com.newscurator.domain.Article;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.dto.response.BiasScoreResponse;
import com.newscurator.dto.response.SummarySlot;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.repository.SummaryRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleDetailService {

    private static final Logger log = LoggerFactory.getLogger(ArticleDetailService.class);

    private final ArticleRepository articleRepository;
    private final SummaryRepository summaryRepository;
    private final AiProvider aiProvider;
    private final SummaryService summaryService;
    private final BiasAnalysisRepository biasAnalysisRepository;

    public ArticleDetailService(
            ArticleRepository articleRepository,
            SummaryRepository summaryRepository,
            AiProvider aiProvider,
            SummaryService summaryService,
            BiasAnalysisRepository biasAnalysisRepository) {
        this.articleRepository = articleRepository;
        this.summaryRepository = summaryRepository;
        this.aiProvider = aiProvider;
        this.summaryService = summaryService;
        this.biasAnalysisRepository = biasAnalysisRepository;
    }

    @Transactional
    public ArticleDetailResponse getDetail(Long articleId) {
        return getDetail(articleId, false);
    }

    /**
     * 008 #9: 기사 상세. {@code includeHidden=false}(일반 사용자)면 admin 숨김 기사는 존재 비노출(404),
     * {@code includeHidden=true}(어드민)면 hidden 포함 조회 허용.
     */
    @Transactional
    public ArticleDetailResponse getDetail(Long articleId, boolean includeHidden) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ArticleNotFoundException(articleId));
        if (!includeHidden && article.isAdminHidden()) {
            // 일반 사용자에게 hidden 기사는 목록 제외만으로 부족 — 상세/딥링크 접근도 404
            throw new ArticleNotFoundException(articleId);
        }

        List<Summary> summaries = summaryRepository.findByArticleId(articleId);
        Map<SummaryDepth, Summary> slotMap = summaries.stream()
                .collect(Collectors.toMap(Summary::getDepth, s -> s));

        // DEEP 슬롯 처리
        Summary deepSlot = slotMap.get(SummaryDepth.DEEP);
        deepSlot = processDeepSlot(article, deepSlot);

        // BRIEF 슬롯 처리 (NOT_GENERATED 면 balanced에서 트런케이션)
        Summary briefSlot = slotMap.get(SummaryDepth.BRIEF);
        briefSlot = processBriefSlot(article, briefSlot, slotMap.get(SummaryDepth.BALANCED));

        // 단건 조회: BiasAnalysis 1행 (N+1 아님)
        BiasAnalysis bias = biasAnalysisRepository.findByArticleId(articleId).orElse(null);

        return buildResponse(article, briefSlot, slotMap.get(SummaryDepth.BALANCED), deepSlot, bias);
    }

    private Summary processDeepSlot(Article article, Summary deepSlot) {
        if (deepSlot == null) {
            deepSlot = summaryRepository.save(
                    Summary.builder().article(article).depth(SummaryDepth.DEEP).build());
        }

        SummarySlotStatus status = deepSlot.getStatus();

        if (status == SummarySlotStatus.NOT_GENERATED) {
            return generateDeepSummary(article, deepSlot);
        }

        if (status == SummarySlotStatus.FAILED) {
            // DEEP FAILED: SummaryService.isDeepRetryAllowed 검사
            if (summaryService.isDeepRetryAllowed(deepSlot)) {
                return generateDeepSummary(article, deepSlot);
            }
            // 쿨다운 미경과 또는 retry_count >= limit → 현재 FAILED 상태 그대로 반환 (CHK022)
        }

        return deepSlot;
    }

    private Summary generateDeepSummary(Article article, Summary deepSlot) {
        deepSlot.markPending();
        try {
            String content = aiProvider.summarize(article.getTitle(), "", SummaryDepth.DEEP);
            deepSlot.complete(content);
        } catch (AiProviderException e) {
            log.warn("[DETAIL] DEEP 요약 생성 실패, articleId={}: {}", article.getId(), e.getMessage());
            // DEEP AI 오류 → Summary.status=FAILED + last_attempt_at/retry_count 갱신 + 200 반환 (CHK022)
            deepSlot.failDeepSlot();
        }
        summaryRepository.save(deepSlot);
        return deepSlot;
    }

    private Summary processBriefSlot(Article article, Summary briefSlot, Summary balancedSlot) {
        if (briefSlot == null || briefSlot.getStatus() == SummarySlotStatus.NOT_GENERATED) {
            if (balancedSlot != null && balancedSlot.getStatus() == SummarySlotStatus.COMPLETED) {
                String briefContent = summaryService.truncateForBrief(balancedSlot.getContent());
                if (briefSlot == null) {
                    briefSlot = summaryRepository.save(
                            Summary.builder().article(article).depth(SummaryDepth.BRIEF).build());
                }
                briefSlot.completeWithoutAi(briefContent);
                summaryRepository.save(briefSlot);
            }
        }
        return briefSlot;
    }

    private ArticleDetailResponse buildResponse(
            Article article, Summary brief, Summary balanced, Summary deep, BiasAnalysis bias) {

        // FAILED → OTHER 매핑
        String category = article.getCategoryStatus() == ProcessingStatus.FAILED
                ? "OTHER"
                : (article.getCategory() != null ? article.getCategory().name() : null);

        BiasScoreResponse biasScore = BiasAnalysisService.toResponse(bias);

        return new ArticleDetailResponse(
                article.getId(),
                article.getTitle(),
                article.getAuthor(),
                article.getOriginalUrl(),
                category,
                article.getPublishedAt(),
                article.getFirstCollectedAt(),
                toSlot(brief),
                toSlot(balanced),
                toSlot(deep),
                biasScore);
    }

    private SummarySlot toSlot(Summary summary) {
        if (summary == null) {
            return new SummarySlot("NOT_GENERATED", null, null);
        }
        return new SummarySlot(
                summary.getStatus().name(),
                summary.getContent(),
                summary.getGeneratedAt());
    }
}
