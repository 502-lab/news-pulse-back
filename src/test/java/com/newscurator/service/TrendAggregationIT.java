package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import java.time.OffsetDateTime;
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
 * T019: 트렌드 집계 통합 테스트 (Testcontainers 실 PostgreSQL + 실 Nori 추출).
 * 추출→슬롯 저장, 재집계 멱등(article_count 불변), summary-race 게이팅(recent-PENDING skip).
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
class TrendAggregationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_trend_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TrendAggregationService service;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, summaries, article_sources,"
                        + " source_daily_usage, articles, sources RESTART IDENTITY CASCADE");
    }

    /** COMPLETED 기사 + BALANCED 요약 저장 */
    private long completedArticle(String url, String title, String balanced, OffsetDateTime collectedAt) {
        Article a = Article.builder()
                .normalizedUrl(url).originalUrl(url).title(title)
                .publishedAt(collectedAt).firstCollectedAt(collectedAt)
                .expiresAt(collectedAt.plusDays(90)).build();
        a.completeSummary();
        Article saved = articleRepository.save(a);
        jdbcTemplate.update("""
                INSERT INTO summaries (article_id, depth, status, content, ai_generated, created_at, updated_at)
                VALUES (?, 'BALANCED', 'COMPLETED', ?, true, NOW(), NOW())
                """, saved.getId(), balanced);
        return saved.getId();
    }

    private long pendingArticle(String url, String title, OffsetDateTime collectedAt) {
        Article a = Article.builder()
                .normalizedUrl(url).originalUrl(url).title(title)
                .publishedAt(collectedAt).firstCollectedAt(collectedAt)
                .expiresAt(collectedAt.plusDays(90)).build();
        return articleRepository.save(a).getId(); // summary_status=PENDING(default)
    }

    private long keywordRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM article_keyword", Long.class);
    }

    @Test
    @DisplayName("추출→슬롯 저장, 재집계 article_count 불변(멱등)")
    void aggregate_thenReaggregate_idempotent() {
        completedArticle("u/1", "정부 금리 인상", "한국은행 금리 인상 발표 경제 영향",
                OffsetDateTime.now().minusHours(2));
        completedArticle("u/2", "금리 동결 전망", "시장 금리 동결 예상 경제 분석",
                OffsetDateTime.now().minusHours(2));

        service.aggregate();

        long kw1 = keywordRows();
        Long slotRows1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trend_keyword_slot", Long.class);
        assertThat(kw1).isGreaterThan(0);
        assertThat(slotRows1).isGreaterThan(0L);
        // '금리'는 2개 기사에 등장 → 해당 슬롯·카테고리 article_count=2
        Integer geumriCount = jdbcTemplate.queryForObject(
                "SELECT MAX(article_count) FROM trend_keyword_slot WHERE term = '금리'", Integer.class);
        assertThat(geumriCount).isEqualTo(2);

        // 재집계: 멱등 — article_keyword 증가 없음, article_count 동일
        service.aggregate();

        assertThat(keywordRows()).isEqualTo(kw1);
        Integer geumriCount2 = jdbcTemplate.queryForObject(
                "SELECT MAX(article_count) FROM trend_keyword_slot WHERE term = '금리'", Integer.class);
        assertThat(geumriCount2).isEqualTo(2);
    }

    @Test
    @DisplayName("summary-race 게이팅: 수집 1h 이내 PENDING은 이번 run skip")
    void recentPending_skipped() {
        // 1h 이내 PENDING → 제외
        pendingArticle("u/recent", "최근 미요약 기사", OffsetDateTime.now().minusMinutes(10));
        // 1h 경과 PENDING → 제목만 추출 포함
        pendingArticle("u/old", "오래된 미요약 정책 발표", OffsetDateTime.now().minusHours(3));

        service.aggregate();

        long recentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_keyword ak JOIN articles a ON a.id=ak.article_id"
                        + " WHERE a.normalized_url = 'u/recent'", Long.class);
        long oldRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_keyword ak JOIN articles a ON a.id=ak.article_id"
                        + " WHERE a.normalized_url = 'u/old'", Long.class);

        assertThat(recentRows).isZero();      // recent-PENDING 제외
        assertThat(oldRows).isGreaterThan(0); // 1h 경과 PENDING은 제목만 추출
    }
}
