package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.enums.Category;
import com.newscurator.dto.response.HeatmapCellResponse;
import com.newscurator.dto.response.WordcloudItemResponse;
import com.newscurator.repository.ArticleRepository;
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
 * T030/크라운주얼: 히트맵·워드클라우드 통합 테스트 (실 PostgreSQL).
 *
 * <p>히트맵 핵심: 한 (slot,category)에 기사 N건을 각각 다중 키워드로 심어도 셀 == N(DISTINCT 기사 수).
 * per-term SUM이면 N 초과로 깨지는 discriminating 단언. 워드클라우드: term weight + min-freq + 윈도우 바운드.
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
class TrendHeatmapWordcloudIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_heatmap_it")
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
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, summaries, article_sources,"
                        + " source_daily_usage, articles, sources RESTART IDENTITY CASCADE");
        // 캐시(R-006)도 DB와 함께 초기화 — 메서드 간 캐시 누수 방지(테스트 격리)
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    /** 기사 1건 저장(카테고리·수집시각) + article_keyword 다중 term 삽입. */
    private long article(String url, Category cat, OffsetDateTime collectedAt, String... terms) {
        Article a = Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(collectedAt).firstCollectedAt(collectedAt)
                .expiresAt(collectedAt.plusDays(90)).build();
        a.completeCategory(cat);
        long id = articleRepository.save(a).getId();
        for (String term : terms) {
            jdbcTemplate.update(
                    "INSERT INTO article_keyword (article_id, term, created_at) VALUES (?, ?, NOW())",
                    id, term);
        }
        return id;
    }

    private void slot(String term, int articleCount, OffsetDateTime slotStart) {
        jdbcTemplate.update("""
                INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
                VALUES (?, 'POLITICS', ?, ?, NOW())
                """, slotStart, term, articleCount);
    }

    @Test
    @DisplayName("히트맵 셀 == DISTINCT 기사 수(키워드 행 수 아님) + 카테고리/시간버킷 분리")
    void heatmap_distinctArticleCount_notPerTermSum() {
        OffsetDateTime h2 = OffsetDateTime.now().minusHours(2);
        OffsetDateTime h5 = OffsetDateTime.now().minusHours(5);

        // POLITICS, 같은 시간버킷(h2): 기사 3건, 각 다중 키워드(총 8 article_keyword 행)
        article("p/1", Category.POLITICS, h2, "금리", "인상", "정책"); // 3
        article("p/2", Category.POLITICS, h2, "금리", "경제");        // 2
        article("p/3", Category.POLITICS, h2, "부동산", "대출", "규제"); // 3  → 합계 8행, DISTINCT 기사 3
        // ECONOMY_FINANCE, 같은 h2: 기사 2건
        article("e/1", Category.ECONOMY_FINANCE, h2, "환율", "달러");
        article("e/2", Category.ECONOMY_FINANCE, h2, "환율", "수출");
        // POLITICS, 다른 시간버킷(h5): 기사 1건
        article("p/4", Category.POLITICS, h5, "총선");

        List<HeatmapCellResponse> cells = trendQueryService.getHeatmap(24);

        // (POLITICS, h2): DISTINCT 기사 3 (per-term SUM이면 8로 깨짐)
        HeatmapCellResponse polH2 = cell(cells, "POLITICS", h2);
        assertThat(polH2.intensity()).isEqualTo(3);
        // (ECONOMY_FINANCE, h2): 2
        assertThat(cell(cells, "ECONOMY_FINANCE", h2).intensity()).isEqualTo(2);
        // (POLITICS, h5): 1 — 시간버킷 분리
        assertThat(cell(cells, "POLITICS", h5).intensity()).isEqualTo(1);
        // 셀 3개(분리 정확성)
        assertThat(cells).hasSize(3);
    }

    @Test
    @DisplayName("워드클라우드: term별 weight 정확 + min-freq(<2) 제외 + 윈도우 바운드")
    void wordcloud_weight_minFreq_window() {
        OffsetDateTime cur = OffsetDateTime.now().minusHours(3);
        OffsetDateTime cur2 = OffsetDateTime.now().minusHours(10);
        OffsetDateTime outside = OffsetDateTime.now().minusHours(30); // 24h 밖

        slot("금리", 3, cur);
        slot("금리", 2, cur2);   // 금리 weight = 5
        slot("인상", 3, cur);    // weight 3
        slot("노이즈", 1, cur);  // weight 1 (<2) → 제외
        slot("옛날", 10, outside); // 윈도우 밖 → 제외

        List<WordcloudItemResponse> cloud = trendQueryService.getWordcloud(24);

        assertThat(cloud).extracting(WordcloudItemResponse::term)
                .containsExactly("금리", "인상")  // weight 내림차순, 노이즈/옛날 제외
                .doesNotContain("노이즈", "옛날");
        assertThat(byTerm(cloud, "금리").weight()).isEqualTo(5);
        assertThat(byTerm(cloud, "인상").weight()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 윈도우 → 빈 격자/목록")
    void emptyWindow() {
        assertThat(trendQueryService.getHeatmap(24)).isEmpty();
        assertThat(trendQueryService.getWordcloud(24)).isEmpty();
    }

    private static HeatmapCellResponse cell(
            List<HeatmapCellResponse> cells, String category, OffsetDateTime hour) {
        // 같은 시간버킷(date_trunc hour) + 카테고리 매칭
        return cells.stream()
                .filter(c -> c.category().equals(category)
                        && c.slotStart().getHour() == hour.toInstant()
                                .atOffset(java.time.ZoneOffset.UTC).getHour())
                .findFirst().orElseThrow(() ->
                        new AssertionError("cell not found: " + category + " @ " + hour));
    }

    private static WordcloudItemResponse byTerm(List<WordcloudItemResponse> list, String term) {
        return list.stream().filter(w -> w.term().equals(term)).findFirst().orElseThrow();
    }
}
