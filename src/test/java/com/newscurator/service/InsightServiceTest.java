package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.config.InsightsRecommendationProperties;
import com.newscurator.dto.response.InsightResponse;
import com.newscurator.repository.InsightAggregationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 010 T011 — InsightService 단위: 표본<5 분기(분포 null·카운트 반환·분포 쿼리 skip) + 표본≥5 매핑. */
@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock private InsightAggregationRepository repository;

    private final UUID acc = UUID.randomUUID();

    private InsightService service() {
        return new InsightService(
                repository, new InsightsRecommendationProperties(0.5, 0.3, 0.2, 14, 5, 10));
    }

    @Test
    void belowSampleThreshold_countsOnly_distributionsNull_noDistributionQueries() {
        when(repository.countReadArticles(acc)).thenReturn(3L); // < 5
        when(repository.countBookmarks(acc)).thenReturn(2L);

        InsightResponse r = service().getInsights(acc);

        assertThat(r.readCount()).isEqualTo(3L);
        assertThat(r.bookmarkCount()).isEqualTo(2L);
        assertThat(r.sampleSufficient()).isFalse();
        assertThat(r.topCategory()).isNull();
        assertThat(r.categoryDistribution()).isNull();
        assertThat(r.keywordDistribution()).isNull();
        assertThat(r.topOutlets()).isNull();
        assertThat(r.biasDistribution()).isNull();
        // 분포 쿼리 skip(쿼리 수 절감)
        verify(repository, never()).categoryDistribution(any());
        verify(repository, never()).biasDistribution(any());
    }

    @Test
    void atOrAboveThreshold_mapsDistributions() {
        when(repository.countReadArticles(acc)).thenReturn(6L); // >= 5
        when(repository.countBookmarks(acc)).thenReturn(1L);
        when(repository.categoryDistribution(acc))
                .thenReturn(List.of(new Object[] {"TECH", 4L}, new Object[] {"POLITICS", 1L}));
        when(repository.keywordDistribution(acc, 5))
                .thenReturn(List.<Object[]>of(new Object[] {"AI", 3L}));
        when(repository.topOutlets(acc, 5))
                .thenReturn(List.<Object[]>of(new Object[] {"연합뉴스", 2L}));
        when(repository.biasDistribution(acc))
                .thenReturn(List.<Object[]>of(new Object[] {40.0, 35.0, 25.0, 5L}));

        InsightResponse r = service().getInsights(acc);

        assertThat(r.sampleSufficient()).isTrue();
        assertThat(r.topCategory()).isEqualTo("TECH");
        assertThat(r.categoryDistribution()).hasSize(2);
        assertThat(r.categoryDistribution().get(0).percent()).isEqualTo(80.0); // 4/5
        assertThat(r.biasDistribution().liberalPercent()).isEqualTo(40.0);
        assertThat(r.biasDistribution().total()).isEqualTo(5L);
    }
}
