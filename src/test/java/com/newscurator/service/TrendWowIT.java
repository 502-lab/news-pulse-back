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
 * T033/크라운주얼: WoW(주간 대비) 급상승 통합 테스트 (실 PostgreSQL).
 *
 * <p>US1(24h Top5)과의 구분점 = 주간 윈도우 경계. 핵심:
 * (1) cur주 급상승(평활비 큼)이 cur주 flat-대형보다 위(count 정렬이면 깨짐),
 * (2) prev주=0 → isNew + deltaPct null, (3) cur<2 제외,
 * (4) ★ prev주 슬롯이 cur주 합산에 안 섞임(count=cur주 합만) — 윈도우 경계 검증.
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
class TrendWowIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_wow_it")
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
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE trend_keyword_slot RESTART IDENTITY");
        // 캐시(R-006)도 DB와 함께 초기화 — 메서드 간 캐시 누수 방지(테스트 격리)
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    private void slot(String term, int articleCount, OffsetDateTime slotStart) {
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (?, 'POLITICS', ?, ?, NOW())
                """, slotStart, term, articleCount);
    }

    @Test
    @DisplayName("주간 경계 + 평활비 정렬 + isNew/null-delta + cur<2 제외 + prev주 미혼입")
    void wow_weeklyBoundary_smoothingSort() {
        OffsetDateTime curWeek = OffsetDateTime.now().minusDays(3);   // 최근 7일(cur주)
        OffsetDateTime prevWeek = OffsetDateTime.now().minusDays(10); // 7~14일 전(prev주)

        // 급상승: cur=5, prev=0 → isNew, 평활비 (5+1)/(0+1)=6.0
        slot("급상승", 5, curWeek);
        // flat-대형: cur=50, prev=50 → 평활비 (50+1)/(50+1)=1.0 (count는 가장 크지만 추세 평평)
        slot("대형평평", 50, curWeek);
        slot("대형평평", 50, prevWeek);
        // 경계 검증: cur=3, prev=100 → count는 cur주 합(3)만, prev(100)은 delta에만 사용
        slot("경계", 3, curWeek);
        slot("경계", 100, prevWeek);
        // 노이즈: cur=1 (<2) → 제외
        slot("노이즈", 1, curWeek);

        List<TrendKeywordResponse> wow = trendQueryService.getWow();

        // 노이즈 제외
        assertThat(wow).extracting(TrendKeywordResponse::term).doesNotContain("노이즈");
        // 평활비 정렬(count 정렬이면 대형평평이 1위여야 하나, 급상승이 위 = discriminating)
        assertThat(wow).extracting(TrendKeywordResponse::term)
                .containsExactly("급상승", "대형평평", "경계");

        TrendKeywordResponse rRise = byTerm(wow, "급상승");
        assertThat(rRise.count()).isEqualTo(5);
        assertThat(rRise.isNew()).isTrue();
        assertThat(rRise.deltaPct()).isNull();              // prev=0 → null

        TrendKeywordResponse rBig = byTerm(wow, "대형평평");
        assertThat(rBig.count()).isEqualTo(50);
        assertThat(rBig.isNew()).isFalse();
        assertThat(rBig.deltaPct()).isCloseTo(0.0, within(0.01)); // 50 vs 50 → 0%

        // ★ 윈도우 경계: count == cur주 합(3), prev주(100)이 cur에 섞이지 않음(103 아님)
        TrendKeywordResponse rEdge = byTerm(wow, "경계");
        assertThat(rEdge.count()).isEqualTo(3);
        assertThat(rEdge.isNew()).isFalse();
        // deltaPct는 prev=100 사용: (3-100)/100 = -97%
        assertThat(rEdge.deltaPct()).isCloseTo(-97.0, within(0.01));
    }

    @Test
    @DisplayName("데이터 없음 → 빈 목록")
    void wow_empty() {
        assertThat(trendQueryService.getWow()).isEmpty();
    }

    private static TrendKeywordResponse byTerm(List<TrendKeywordResponse> list, String term) {
        return list.stream().filter(r -> r.term().equals(term)).findFirst().orElseThrow();
    }
}
