package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.dto.response.TrendKeywordResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T025: Top5 집계 정확성 통합 테스트 (실 PostgreSQL).
 * 노이즈컷(cur<2 제외), 평활비 정렬(isNew가 큰-but-flat보다 위), deltaPct(raw)/isNew, 빈 목록.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {
            "app.client.gemini.api-key=test-key",
            "app.client.gemini.base-url=http://localhost:9999",
            "app.client.naver.client-id=test-id",
            "app.client.naver.client-secret=test-secret",
            "app.client.naver.base-url=http://localhost:9999",
            "app.scheduler.enabled=false"
        })
class TrendTop5IT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_top5_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TrendQueryService trendQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE trend_keyword_slot RESTART IDENTITY");
    }

    /** 슬롯 1행 삽입 (slot_start 지정 → 현재/직전 윈도우 배치). */
    private void slot(String term, int articleCount, OffsetDateTime slotStart) {
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (?, 'POLITICS', ?, ?, NOW())
                """, slotStart, term, articleCount);
    }

    @Test
    @DisplayName("노이즈컷(cur<2 제외) + 평활비 정렬(신규가 큰-but-flat보다 위) + deltaPct/isNew")
    void top5_noiseCut_smoothingSort_isNew() {
        OffsetDateTime cur = OffsetDateTime.now().minusHours(12);   // 현재 윈도우(24h 내)
        OffsetDateTime prev = OffsetDateTime.now().minusHours(36);  // 직전 윈도우(24~48h)

        // 신규: cur=2(노이즈컷 통과 최소), prev=0 → isNew, 평활비 (2+1)/(0+1)=3.0
        slot("신규이슈", 2, cur);
        // 큰-but-flat: cur=50, prev=49 → 평활비 (50+1)/(49+1)=1.02
        slot("기존대형", 50, cur);
        slot("기존대형", 49, prev);
        // 노이즈: cur=1 (<2) → 제외
        slot("노이즈", 1, cur);

        List<TrendKeywordResponse> top5 = trendQueryService.getTop5(null);

        // 노이즈 제외
        assertThat(top5).extracting(TrendKeywordResponse::term).doesNotContain("노이즈");
        // 평활비 정렬: 신규이슈(3.0)가 기존대형(1.02)보다 위
        assertThat(top5).extracting(TrendKeywordResponse::term)
                .containsExactly("신규이슈", "기존대형");

        TrendKeywordResponse rNew = top5.get(0);
        assertThat(rNew.term()).isEqualTo("신규이슈");
        assertThat(rNew.count()).isEqualTo(2);
        assertThat(rNew.isNew()).isTrue();
        assertThat(rNew.deltaPct()).isNull();          // prev=0 → null

        TrendKeywordResponse rBig = top5.get(1);
        assertThat(rBig.isNew()).isFalse();
        assertThat(rBig.deltaPct()).isCloseTo(100.0 * (50 - 49) / 49, within(0.01)); // raw ≈2.04%
    }

    @Test
    @DisplayName("카테고리 필터: 다른 카테고리 슬롯 제외")
    void top5_categoryFilter() {
        OffsetDateTime cur = OffsetDateTime.now().minusHours(12);
        slot("정치키워드", 3, cur); // POLITICS
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (?, 'ECONOMY_FINANCE', '경제키워드', 5, NOW())
                """, cur);

        List<TrendKeywordResponse> politics = trendQueryService.getTop5("POLITICS");

        assertThat(politics).extracting(TrendKeywordResponse::term)
                .containsExactly("정치키워드")
                .doesNotContain("경제키워드");
    }

    @Test
    @DisplayName("데이터 없음 → 빈 목록")
    void top5_empty() {
        assertThat(trendQueryService.getTop5(null)).isEmpty();
    }
}
