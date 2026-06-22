package com.newscurator.service;

import com.newscurator.config.TrendProperties;
import com.newscurator.dto.response.HeatmapCellResponse;
import com.newscurator.dto.response.TrendKeywordResponse;
import com.newscurator.dto.response.WordcloudItemResponse;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.TrendKeywordSlotRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트렌드 조회 서비스 (read). 저장된 trend_keyword_slot 집계를 서빙 — on-the-fly 재계산 없음(FR-013).
 */
@Service
public class TrendQueryService {

    private static final int TOP5_LIMIT = 5;
    private static final int WOW_LIMIT = 20;
    private static final int WEEK_DAYS = 7;

    private final TrendKeywordSlotRepository slotRepository;
    private final ArticleKeywordRepository articleKeywordRepository;
    private final TrendProperties trendProperties;

    public TrendQueryService(
            TrendKeywordSlotRepository slotRepository,
            ArticleKeywordRepository articleKeywordRepository,
            TrendProperties trendProperties) {
        this.slotRepository = slotRepository;
        this.articleKeywordRepository = articleKeywordRepository;
        this.trendProperties = trendProperties;
    }

    /**
     * 지금 뜨는 키워드 Top5 (FR-004). 최근 24h 윈도우.
     * 정렬은 평활비 (cur+k)/(prev+k)(SQL), 응답 deltaPct는 raw %, prev=0은 null+isNew.
     * 노이즈컷: cur < minArticleCount 제외(cur<2). category null이면 전체.
     */
    @Transactional(readOnly = true)
    public List<TrendKeywordResponse> getTop5(String category) {
        Instant now = Instant.now();
        int window = trendProperties.top5WindowHours();
        Instant curStart = now.minus(window, ChronoUnit.HOURS);
        Instant prevStart = now.minus(2L * window, ChronoUnit.HOURS);

        List<Object[]> rows = slotRepository.findRisingKeywords(
                curStart, prevStart,
                (category == null || category.isBlank()) ? null : category,
                trendProperties.minArticleCount(),
                trendProperties.smoothingK(),
                TOP5_LIMIT);

        return rows.stream().map(TrendQueryService::toKeyword).toList();
    }

    /**
     * WoW(주간 대비) 급상승 (FR-007). cur주(최근 7d) vs prev주(7~14d 전).
     * US1과 동일한 평활비 정렬·isNew·null-delta 로직을 "주간 윈도우"에 적용(24h Top5와의 구분점 = 주간 경계).
     * prev주 슬롯은 cur주 합산에 섞이지 않는다(쿼리 FILTER 경계). cur<2 제외, prev=0 → isNew+deltaPct null.
     */
    @Transactional(readOnly = true)
    public List<TrendKeywordResponse> getWow() {
        Instant now = Instant.now();
        Instant curStart = now.minus(WEEK_DAYS, ChronoUnit.DAYS);          // 최근 7일
        Instant prevStart = now.minus(2L * WEEK_DAYS, ChronoUnit.DAYS);    // 7~14일 전 경계

        List<Object[]> rows = slotRepository.findRisingKeywords(
                curStart, prevStart,
                null, // WoW는 전체 카테고리
                trendProperties.minArticleCount(),
                trendProperties.smoothingK(),
                WOW_LIMIT);

        return rows.stream().map(TrendQueryService::toKeyword).toList();
    }

    /**
     * 히트맵 (FR-006): (시간버킷 × 카테고리) 격자의 기사 볼륨 = DISTINCT 기사 수.
     * per-term SUM이 아니라 article_keyword JOIN articles의 DISTINCT 기사(과대계상 방지).
     */
    @Transactional(readOnly = true)
    public List<HeatmapCellResponse> getHeatmap(int windowHours) {
        Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        return articleKeywordRepository.heatmap(windowStart).stream()
                .map(row -> new HeatmapCellResponse(
                        toInstant(row[0]).atOffset(ZoneOffset.UTC),
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /**
     * 워드클라우드 (FR-006): term별 weight = 윈도우 내 article_count 합. min-freq(<2) 제외.
     */
    @Transactional(readOnly = true)
    public List<WordcloudItemResponse> getWordcloud(int windowHours) {
        Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        return slotRepository.wordcloud(windowStart, trendProperties.minArticleCount()).stream()
                .map(row -> new WordcloudItemResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    /** date_trunc 결과는 드라이버/Hibernate에 따라 Instant 또는 Timestamp로 옴 — 둘 다 처리. */
    private static Instant toInstant(Object ts) {
        if (ts instanceof Instant i) {
            return i;
        }
        return ((Timestamp) ts).toInstant();
    }

    private static TrendKeywordResponse toKeyword(Object[] row) {
        String term = (String) row[0];
        long cur = ((Number) row[1]).longValue();
        long prev = ((Number) row[2]).longValue();
        boolean isNew = prev == 0;
        Double deltaPct = prev == 0 ? null : 100.0 * (cur - prev) / prev;
        return new TrendKeywordResponse(term, cur, deltaPct, isNew);
    }
}
