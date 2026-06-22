package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T045: two-tx claimer 동시성·lease 회수 전용 통합 테스트 (Testcontainers 실 PostgreSQL).
 *
 * <p>(a) 2워커 동시 claim → 합산 1건 (FOR UPDATE SKIP LOCKED 배타성),
 * (b) in-flight lease 보호 (claim 직후 next_retry_at 미래 → 재조회 제외),
 * (c) lease 만료 stuck 회수 (PROCESSING + next_retry_at<=NOW() 재claim).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BiasClaimerConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_bias_concurrency_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.scheduler.bias.lease-minutes", () -> "5");
    }

    @Autowired private BiasAnalysisClaimer claimer;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private BiasAnalysisRepository biasAnalysisRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, summaries, article_sources, source_daily_usage,"
                        + " articles, sources RESTART IDENTITY CASCADE");
    }

    private long insertArticle(String url) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build());
        return a.getId();
    }

    @Test
    @DisplayName("2워커 동시 claim → 합산 정확히 1건 (SKIP LOCKED 배타성)")
    void twoWorkers_claimSameRow_onlyOneWins() throws Exception {
        long articleId = insertArticle("https://ex.com/concurrent");
        biasAnalysisRepository.save(BiasAnalysis.builder().articleId(articleId).build());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> task = () -> claimer.claimBatch(10).size();
        Future<Integer> f1 = pool.submit(task);
        Future<Integer> f2 = pool.submit(task);
        int total = f1.get() + f2.get();
        pool.shutdown();

        assertThat(total).isEqualTo(1);
        // 행은 PROCESSING + lease 미래로 1회만 점유됨
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bias_analysis WHERE article_id = ?", String.class, articleId))
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("in-flight lease 보호: claim 직후 재claim 시 빈 결과")
    void inFlight_leaseProtects_secondClaimEmpty() {
        long articleId = insertArticle("https://ex.com/inflight");
        biasAnalysisRepository.save(BiasAnalysis.builder().articleId(articleId).build());

        List<BiasAnalysis> first = claimer.claimBatch(10);
        List<BiasAnalysis> second = claimer.claimBatch(10);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty(); // next_retry_at = NOW()+5m(미래)라 제외
    }

    @Test
    @DisplayName("lease 만료 stuck 회수: PROCESSING + next_retry_at 과거 → 재claim")
    void leaseExpired_stuckRow_reclaimed() {
        long articleId = insertArticle("https://ex.com/stuck");
        // 크래시로 PROCESSING에 고아가 된 행: next_retry_at을 과거로
        jdbcTemplate.update("""
                INSERT INTO bias_analysis
                    (article_id, status, attempt_count, next_retry_at, created_at, updated_at)
                VALUES (?, 'PROCESSING', 0, NOW() - INTERVAL '10 minutes', NOW(), NOW())
                """, articleId);

        List<BiasAnalysis> claimed = claimer.claimBatch(10);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getArticleId()).isEqualTo(articleId);
    }
}
