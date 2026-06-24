package com.newscurator.service;

import com.newscurator.config.InsightsRecommendationProperties;
import com.newscurator.dto.response.BiasDistributionResponse;
import com.newscurator.dto.response.InsightResponse;
import com.newscurator.dto.response.InsightResponse.CategoryShare;
import com.newscurator.dto.response.InsightResponse.KeywordCount;
import com.newscurator.dto.response.InsightResponse.OutletCount;
import com.newscurator.repository.InsightAggregationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 010 개인 소비 인사이트(US1) — 6항목 온디맨드 집계. 본인 스코프, 숨김 제외, 편향 DONE만.
 *
 * <p>표본 분기: 읽은 고유 기사 &lt; {@code minSampleSize}(5)이면 분포 쿼리를 생략하고 sampleSufficient=false로
 * 카운트만 반환(쿼리 수 절감 + 노이즈 통계 회피, NPE·분모0 없음).
 */
@Service
public class InsightService {

    /** 분포 표시 상한(언론사·키워드) — 표현용(랭킹 튜닝 파라미터 아님). */
    private static final int TOP_K = 5;

    private final InsightAggregationRepository repository;
    private final InsightsRecommendationProperties props;

    public InsightService(
            InsightAggregationRepository repository, InsightsRecommendationProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public InsightResponse getInsights(UUID accountId) {
        long readCount = repository.countReadArticles(accountId);
        long bookmarkCount = repository.countBookmarks(accountId);

        // 표본 부족: 카운트만, 분포 미산출(분포 쿼리 skip)
        if (readCount < props.minSampleSize()) {
            return new InsightResponse(
                    readCount, bookmarkCount, false, null, null, null, null, null);
        }

        List<Object[]> categoryRows = repository.categoryDistribution(accountId);
        long categoryTotal = categoryRows.stream().mapToLong(r -> toLong(r[1])).sum();
        List<CategoryShare> categoryDist =
                categoryRows.stream()
                        .map(
                                r ->
                                        new CategoryShare(
                                                (String) r[0],
                                                categoryTotal == 0
                                                        ? 0.0
                                                        : 100.0 * toLong(r[1]) / categoryTotal))
                        .toList();
        String topCategory = categoryDist.isEmpty() ? null : categoryDist.get(0).category();

        List<KeywordCount> keywordDist =
                repository.keywordDistribution(accountId, TOP_K).stream()
                        .map(r -> new KeywordCount((String) r[0], toLong(r[1])))
                        .toList();

        List<OutletCount> outlets =
                repository.topOutlets(accountId, TOP_K).stream()
                        .map(r -> new OutletCount((String) r[0], toLong(r[1])))
                        .toList();

        Object[] bias = repository.biasDistribution(accountId).get(0);
        BiasDistributionResponse biasDist =
                new BiasDistributionResponse(
                        toDouble(bias[0]), toDouble(bias[1]), toDouble(bias[2]), toLong(bias[3]));

        return new InsightResponse(
                readCount,
                bookmarkCount,
                true,
                topCategory,
                categoryDist,
                keywordDist,
                outlets,
                biasDist);
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Double toDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
