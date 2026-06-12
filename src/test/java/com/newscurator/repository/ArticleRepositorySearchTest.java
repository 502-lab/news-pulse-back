package com.newscurator.repository;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class ArticleRepositorySearchTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_search_repo_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM summaries");
        jdbcTemplate.execute("DELETE FROM article_sources");
        jdbcTemplate.execute("DELETE FROM articles");
    }

    // ── [SRT1] pg_bigm: "경제" → "경제성장" included, 무관 기사 excluded ──────────

    @Test
    @DisplayName("[SRT1] '경제' bigram 검색 → 경제성장 포함, 날씨 소식 미포함")
    void search_koreanBigram_includesRelatedExcludesUnrelated() {
        Long econId = insertArticle("경제성장의 비결", OffsetDateTime.now().minusHours(1));
        Long unrelId = insertArticle("날씨 소식: 맑음", OffsetDateTime.now().minusHours(2));

        List<Object[]> results = articleRepository.searchByQuery("경제", 20);

        List<Long> foundIds = results.stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();

        assertThat(foundIds).contains(econId);
        assertThat(foundIds).doesNotContain(unrelId);
    }

    // ── [SRT2] GREATEST(bigm_similarity): summary-strong 기사 > title-only 기사 ──

    @Test
    @DisplayName("[SRT2] GREATEST(bigm_similarity): 요약 강한 기사가 제목만 일치 기사보다 상위 랭크")
    void search_greatestSimilarity_summaryStrongRanksAboveTitleOnly() {
        // Article B: title "경제면" → bigm_similarity("경제면","경제") ≈ 0.4, no summary
        Long titleOnlyId = insertArticle("경제면", OffsetDateTime.now().minusHours(2));

        // Article A: title "기술 트렌드" (no overlap) + summary content with repeated "경제"
        //            → GREATEST(0, bigm_similarity("경제 경제 경제 경제 경제","경제")) ≈ 0.75
        Long summaryStrongId = insertArticle("기술 트렌드 동향", OffsetDateTime.now().minusHours(1));
        jdbcTemplate.update(
                "INSERT INTO summaries"
                        + " (article_id, depth, status, content, retry_count, ai_generated, created_at, updated_at)"
                        + " VALUES (?, 'BALANCED', 'COMPLETED', ?, 0, true, NOW(), NOW())",
                summaryStrongId,
                "경제 경제 경제 경제 경제");

        List<Object[]> results = articleRepository.searchByQuery("경제", 20);

        List<Long> orderedIds = results.stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();

        // summary-strong article must rank above title-only article
        assertThat(orderedIds).contains(summaryStrongId, titleOnlyId);
        assertThat(orderedIds.indexOf(summaryStrongId))
                .as("요약 강한 기사 순위 < 제목만 기사 순위 (낮을수록 상위)")
                .isLessThan(orderedIds.indexOf(titleOnlyId));
    }

    // ── [SRT3] LIKE '%' || :q || '%' binding — not literal '%:q%' ─────────────

    @Test
    @DisplayName("[SRT3] LIKE 바인딩 확인: 결과 > 0 (리터럴 '%:q%' 버그 방지)")
    void search_likeBinding_returnsNonEmptyResults() {
        insertArticle("경제성장이 이어진다", OffsetDateTime.now().minusHours(1));

        List<Object[]> results = articleRepository.searchByQuery("경제", 20);

        assertThat(results).isNotEmpty();
    }

    // ── [SRT4] 90일 필터: published_at >= NOW() - 90일 ─────────────────────────

    @Test
    @DisplayName("[SRT4] 90일 초과 기사 → 검색 결과 제외 단언")
    void search_oldArticle_excludedBy90DayFilter() {
        Long oldId = insertArticle("경제 오래된 기사", OffsetDateTime.now().minusDays(91));
        Long recentId = insertArticle("경제 최신 기사", OffsetDateTime.now().minusHours(1));

        List<Object[]> results = articleRepository.searchByQuery("경제", 20);

        List<Long> foundIds = results.stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();

        assertThat(foundIds).contains(recentId);
        assertThat(foundIds).doesNotContain(oldId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long insertArticle(String title, OffsetDateTime publishedAt) {
        String url = "https://search-repo-test.example.com/" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles"
                        + " (normalized_url, original_url, title, published_at,"
                        + "  first_collected_at, category_status, summary_status,"
                        + "  expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, title, publishedAt, publishedAt,
                publishedAt.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }
}
