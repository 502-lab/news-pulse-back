package com.newscurator.service;

import com.newscurator.config.TrendProperties;
import com.newscurator.dto.response.TrendKeywordResponse;
import com.newscurator.repository.TrendKeywordSlotRepository;
import java.time.Instant;
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

    private final TrendKeywordSlotRepository slotRepository;
    private final TrendProperties trendProperties;

    public TrendQueryService(
            TrendKeywordSlotRepository slotRepository, TrendProperties trendProperties) {
        this.slotRepository = slotRepository;
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

    private static TrendKeywordResponse toKeyword(Object[] row) {
        String term = (String) row[0];
        long cur = ((Number) row[1]).longValue();
        long prev = ((Number) row[2]).longValue();
        boolean isNew = prev == 0;
        Double deltaPct = prev == 0 ? null : 100.0 * (cur - prev) / prev;
        return new TrendKeywordResponse(term, cur, deltaPct, isNew);
    }
}
