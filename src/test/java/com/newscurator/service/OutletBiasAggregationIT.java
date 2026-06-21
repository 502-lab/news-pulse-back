package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.ArticleSource;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import com.newscurator.dto.response.OutletBiasResponse;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.ArticleSourceRepository;
import com.newscurator.repository.SourceRepository;
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
 * T032: 출처 편향 집계 정확성 통합 테스트 (Testcontainers 실 PostgreSQL).
 * 단순평균·롤링 90일·최소 10건 경계·DONE만 포함 + idx_article_sources_source_id 존재 검증(SC-004).
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
class OutletBiasAggregationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_outlet_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private BiasAnalysisService biasAnalysisService;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleSourceRepository articleSourceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, summaries, article_sources, source_daily_usage,"
                        + " articles, sources RESTART IDENTITY CASCADE");
    }

    private Source saveSource(String name) {
        return sourceRepository.save(Source.builder()
                .name(name).feedUrl("https://feed/" + name).adapterType(SourceAdapterType.RSS)
                .active(true).collectionIntervalMinutes(60).callBudgetDaily(1000).build());
    }

    /** source에 연결된 DONE bias_analysis 1건 생성 (analyzedAt 지정). */
    private void addDoneArticle(Source source, String url, int value, OffsetDateTime analyzedAt) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90)).build());
        articleSourceRepository.save(ArticleSource.builder()
                .article(a).source(source).collectedAt(OffsetDateTime.now()).merge(false).build());
        jdbcTemplate.update("""
                INSERT INTO bias_analysis
                    (article_id, status, value, attempt_count, next_retry_at, analyzed_at, created_at, updated_at)
                VALUES (?, 'DONE', ?, 0, NOW(), ?, NOW(), NOW())
                """, a.getId(), value, analyzedAt);
    }

    private void addPendingArticle(Source source, String url) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90)).build());
        articleSourceRepository.save(ArticleSource.builder()
                .article(a).source(source).collectedAt(OffsetDateTime.now()).merge(false).build());
        jdbcTemplate.update("""
                INSERT INTO bias_analysis
                    (article_id, status, attempt_count, next_retry_at, created_at, updated_at)
                VALUES (?, 'PENDING', 0, NOW(), NOW(), NOW())
                """, a.getId());
    }

    @Test
    @DisplayName("10건 이상 DONE: 단순평균 biasValue + articleCount 반환")
    void tenOrMore_returnsAverage() {
        Source s = saveSource("outletA");
        // 10건, 값 -20 5개 + +20 5개 → 평균 0.0
        for (int i = 0; i < 5; i++) {
            addDoneArticle(s, "https://a/" + i, -20, OffsetDateTime.now().minusDays(1));
        }
        for (int i = 5; i < 10; i++) {
            addDoneArticle(s, "https://a/" + i, 20, OffsetDateTime.now().minusDays(1));
        }

        OutletBiasResponse r = biasAnalysisService.getOutletBias(s.getId());

        assertThat(r.articleCount()).isEqualTo(10);
        assertThat(r.biasValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("10건 미만 DONE: biasValue null, articleCount는 실제 값")
    void belowTen_biasValueNull() {
        Source s = saveSource("outletB");
        for (int i = 0; i < 9; i++) {
            addDoneArticle(s, "https://b/" + i, -30, OffsetDateTime.now().minusDays(1));
        }

        OutletBiasResponse r = biasAnalysisService.getOutletBias(s.getId());

        assertThat(r.articleCount()).isEqualTo(9);
        assertThat(r.biasValue()).isNull();
    }

    @Test
    @DisplayName("90일 밖 DONE 기사 제외 (롤링 90일)")
    void outsideNinetyDays_excluded() {
        Source s = saveSource("outletC");
        // 90일 이내 10건
        for (int i = 0; i < 10; i++) {
            addDoneArticle(s, "https://c/in/" + i, 10, OffsetDateTime.now().minusDays(10));
        }
        // 90일 밖 5건 (집계 제외 대상)
        for (int i = 0; i < 5; i++) {
            addDoneArticle(s, "https://c/out/" + i, -100, OffsetDateTime.now().minusDays(120));
        }

        OutletBiasResponse r = biasAnalysisService.getOutletBias(s.getId());

        assertThat(r.articleCount()).isEqualTo(10);  // 90일 밖 5건 제외
        assertThat(r.biasValue()).isEqualTo(10.0);   // -100 미포함
    }

    @Test
    @DisplayName("DONE만 집계, PENDING 제외")
    void onlyDone_pendingExcluded() {
        Source s = saveSource("outletD");
        for (int i = 0; i < 10; i++) {
            addDoneArticle(s, "https://d/done/" + i, 50, OffsetDateTime.now().minusDays(1));
        }
        for (int i = 0; i < 3; i++) {
            addPendingArticle(s, "https://d/pending/" + i);
        }

        OutletBiasResponse r = biasAnalysisService.getOutletBias(s.getId());

        assertThat(r.articleCount()).isEqualTo(10);  // PENDING 3건 제외
        assertThat(r.biasValue()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("idx_article_sources_source_id 인덱스 존재(JOIN 보조, SC-004)")
    void sourceIdIndexExists() {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_article_sources_source_id'",
                Integer.class);
        assertThat(cnt).isEqualTo(1);
    }
}
