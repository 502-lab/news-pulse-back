package com.newscurator.service.recommendation;

import com.newscurator.config.InsightsRecommendationProperties;
import com.newscurator.domain.Article;
import com.newscurator.dto.response.RecommendationResponse;
import com.newscurator.dto.response.RecommendationResponse.RecommendedArticle;
import com.newscurator.repository.ArticleEventRepository;
import com.newscurator.repository.FollowKeywordRepository;
import com.newscurator.repository.InsightAggregationRepository;
import com.newscurator.repository.UserInterestsRepository;
import com.newscurator.service.ArticleRelevanceScorer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 010 룰베이스 놓친 기사 추천(US2). 후보(14일 비숨김 − 조회 − 저장) 위에 관심사/조회 프로파일 + 트렌드 가중으로
 * 정렬. 가중치는 {@link InsightsRecommendationProperties}(런타임 조정). 임베딩 v2는 {@link RecommendationEngine}
 * 교체로 도입.
 *
 * <p>★ 콜드스타트 4분기(개인화 신호 전무 시에만 트렌드 fallback):
 * <ul>
 *   <li>조회0 AND 관심사0 → 트렌드/최근 fallback(coldStart=true)
 *   <li>조회0 + 관심사有 → 관심사 기반
 *   <li>조회有 + 관심사0 → 조회 프로파일 기반
 *   <li>둘 다 有 → 관심사 + 조회 프로파일 결합
 * </ul>
 */
@Service
public class RuleBasedRecommender implements RecommendationEngine {

    private static final int CANDIDATE_FETCH_LIMIT = 300;
    private static final int TREND_TOP = 30;
    /** 트렌드 매칭 1건당 가산 단위(카테고리 매칭 점수와 비교 가능한 규모). */
    private static final double TREND_UNIT = 50.0;
    /** 조회 프로파일 추출 시 상위 N. */
    private static final int PROFILE_TOP = 5;

    private final InsightAggregationRepository aggregationRepository;
    private final ArticleEventRepository articleEventRepository;
    private final UserInterestsRepository userInterestsRepository;
    private final FollowKeywordRepository followKeywordRepository;
    private final ArticleRelevanceScorer relevanceScorer;
    private final InsightsRecommendationProperties props;

    public RuleBasedRecommender(
            InsightAggregationRepository aggregationRepository,
            ArticleEventRepository articleEventRepository,
            UserInterestsRepository userInterestsRepository,
            FollowKeywordRepository followKeywordRepository,
            ArticleRelevanceScorer relevanceScorer,
            InsightsRecommendationProperties props) {
        this.aggregationRepository = aggregationRepository;
        this.articleEventRepository = articleEventRepository;
        this.userInterestsRepository = userInterestsRepository;
        this.followKeywordRepository = followKeywordRepository;
        this.relevanceScorer = relevanceScorer;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public RecommendationResponse recommend(UUID accountId, int limit) {
        boolean hasHistory = articleEventRepository.countDistinctArticlesByAccount(accountId) > 0;

        List<String> interestCategories =
                userInterestsRepository.findByAccountId(accountId).stream()
                        .map(com.newscurator.domain.UserInterests::getCategory)
                        .toList();
        List<String> interestKeywords =
                followKeywordRepository.findByAccountId(accountId).stream()
                        .map(com.newscurator.domain.FollowKeyword::getKeyword)
                        .toList();
        boolean hasInterests = !interestCategories.isEmpty() || !interestKeywords.isEmpty();
        boolean coldStart = !hasHistory && !hasInterests;

        // ★ 4분기 프로파일 결정
        List<String> userCategories = new ArrayList<>();
        List<String> userKeywords = new ArrayList<>();
        if (hasInterests) {
            userCategories.addAll(interestCategories);
            userKeywords.addAll(interestKeywords);
        }
        if (hasHistory && !hasInterests) {
            // 조회만 있는 경우 조회 프로파일(읽은 카테고리/키워드)로 대체
            userCategories.addAll(readCategories(accountId));
            userKeywords.addAll(readKeywords(accountId));
        }
        // 둘 다 있으면 위 두 if로 관심사만 채워짐 — 조회 프로파일도 결합
        if (hasHistory && hasInterests) {
            userCategories.addAll(readCategories(accountId));
            userKeywords.addAll(readKeywords(accountId));
        }
        // coldStart: 둘 다 비어 userCategories/Keywords 빈 채로 → 트렌드/최근성이 정렬 주도

        List<Article> candidates =
                aggregationRepository.findRecommendationCandidates(
                        accountId, props.candidateDays(), CANDIDATE_FETCH_LIMIT);
        if (candidates.isEmpty()) {
            return new RecommendationResponse(List.of(), coldStart);
        }

        Set<String> trending = new HashSet<>(aggregationRepository.trendingTerms(TREND_TOP));
        Map<Long, Set<String>> candidateKeywords = candidateKeywords(candidates);
        Instant now = Instant.now();

        List<String> cats = userCategories;
        List<String> kws = userKeywords;
        List<RecommendedArticle> items =
                candidates.stream()
                        .map(a -> new Scored(a, score(a, cats, kws, trending, candidateKeywords, now)))
                        .sorted(Comparator.comparingDouble(Scored::score).reversed())
                        .limit(limit)
                        .map(s -> toResponse(s.article(), cats, kws, trending, candidateKeywords))
                        .toList();

        return new RecommendationResponse(items, coldStart);
    }

    private double score(
            Article a,
            List<String> cats,
            List<String> kws,
            Set<String> trending,
            Map<Long, Set<String>> candidateKeywords,
            Instant now) {
        double relevance = relevanceScorer.score(a, cats, kws, now);
        long trendMatches = trendMatchCount(a, trending, candidateKeywords);
        double recency = recencyScore(a, now);
        return props.categoryWeight() * relevance
                + props.trendWeight() * trendMatches * TREND_UNIT
                + props.recencyWeight() * recency;
    }

    private long trendMatchCount(
            Article a, Set<String> trending, Map<Long, Set<String>> candidateKeywords) {
        Set<String> kw = candidateKeywords.getOrDefault(a.getId(), Set.of());
        return kw.stream().filter(trending::contains).count();
    }

    /** 0~100 최근성 점수(후보 윈도우 내 선형 감쇠). */
    private double recencyScore(Article a, Instant now) {
        long hours = Duration.between(a.getPublishedAt().toInstant(), now).toHours();
        long windowHours = (long) props.candidateDays() * 24;
        if (hours < 0 || hours > windowHours) {
            return 0.0;
        }
        return 100.0 * (1.0 - (double) hours / windowHours);
    }

    private RecommendedArticle toResponse(
            Article a,
            List<String> cats,
            List<String> kws,
            Set<String> trending,
            Map<Long, Set<String>> candidateKeywords) {
        String reason;
        boolean categoryMatch = a.getCategory() != null && cats.contains(a.getCategory().name());
        boolean keywordMatch =
                kws.stream().anyMatch(k -> a.getTitle().toLowerCase().contains(k.toLowerCase()));
        if (categoryMatch || keywordMatch) {
            reason = "INTEREST_MATCH";
        } else if (trendMatchCount(a, trending, candidateKeywords) > 0) {
            reason = "TREND";
        } else {
            reason = "RECENT";
        }
        return new RecommendedArticle(
                a.getId(),
                a.getTitle(),
                a.getCategory() == null ? null : a.getCategory().name(),
                a.getPublishedAt().toInstant().atOffset(ZoneOffset.UTC),
                reason);
    }

    private List<String> readCategories(UUID accountId) {
        return aggregationRepository.categoryDistribution(accountId).stream()
                .map(r -> (String) r[0])
                .limit(PROFILE_TOP)
                .toList();
    }

    private List<String> readKeywords(UUID accountId) {
        return aggregationRepository.keywordDistribution(accountId, PROFILE_TOP).stream()
                .map(r -> (String) r[0])
                .toList();
    }

    private Map<Long, Set<String>> candidateKeywords(List<Article> candidates) {
        List<Long> ids = candidates.stream().map(Article::getId).toList();
        return aggregationRepository.keywordsForArticles(ids).stream()
                .collect(
                        Collectors.groupingBy(
                                r -> ((Number) r[0]).longValue(),
                                Collectors.mapping(r -> (String) r[1], Collectors.toSet())));
    }

    private record Scored(Article article, double score) {}
}
