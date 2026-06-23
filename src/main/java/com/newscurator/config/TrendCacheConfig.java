package com.newscurator.config;

import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 트렌드 조회 캐싱(R-006, Redis 미사용). 인메모리 {@link ConcurrentMapCacheManager}.
 *
 * <p>무효화는 TTL이 아니라 <b>집계 TX 커밋 후(afterCommit)</b> 전 캐시 clear:
 * {@code TrendAggregationService.aggregate()}가 활성 TX의 {@code afterCommit} 동기화로 evict를 등록한다.
 * {@code @CacheEvict}는 advisor 순서 미고정으로 커밋 전 evict→stale 재적재 창이 생길 수 있어 쓰지 않는다.
 * 따라서 캐시 신선도 = 집계 주기(기본 10분). 저장된 집계(slot/snapshot)는 집계 사이 불변이라 안전.
 */
@Configuration
@EnableCaching
public class TrendCacheConfig {

    public static final String TOP5 = "trendTop5";
    public static final String WOW = "trendWow";
    public static final String HEATMAP = "trendHeatmap";
    public static final String WORDCLOUD = "trendWordcloud";
    public static final String ISSUES = "trendIssues";

    @Bean
    public CacheManager trendCacheManager() {
        return new ConcurrentMapCacheManager(TOP5, WOW, HEATMAP, WORDCLOUD, ISSUES);
    }

    /** 집계 시 일괄 무효화 대상(캐시 이름 묶음). */
    public static List<String> allTrendCaches() {
        return List.of(TOP5, WOW, HEATMAP, WORDCLOUD, ISSUES);
    }
}
