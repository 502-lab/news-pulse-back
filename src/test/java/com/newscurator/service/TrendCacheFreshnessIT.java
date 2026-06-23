package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T044 캐시 신선도 IT (실 PostgreSQL): afterCommit 무효화 전략 검증.
 *
 * <p>① 집계 커밋 후 evict → 다음 read가 신규 데이터 즉시 반영(중간엔 캐시가 stale을 서빙해 캐시 활성 입증).
 * ② 집계 TX 롤백 시 evict 미발생(afterCommit) → 캐시 그대로(커밋 전 evict 창 제거 증명).
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
class TrendCacheFreshnessIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_cache_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TrendAggregationService aggregationService;
    @Autowired private TrendQueryService queryService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CacheManager cacheManager;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, issue_snapshot, summaries,"
                        + " article_sources, source_daily_usage, articles, sources RESTART IDENTITY CASCADE");
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    /** 동일 본문 N개 기사 저장 → 모든 키워드가 N기사 동시출현 → 정확히 1 클러스터. */
    private void seedTopic(String urlPrefix, String text, OffsetDateTime collectedAt) {
        for (int i = 0; i < 3; i++) {
            Article a = Article.builder()
                    .normalizedUrl(urlPrefix + "/" + i).originalUrl(urlPrefix + "/" + i)
                    .title(text).publishedAt(collectedAt).firstCollectedAt(collectedAt)
                    .expiresAt(collectedAt.plusDays(90)).build();
            a.completeSummary();
            Article saved = articleRepository.save(a);
            jdbcTemplate.update("""
                    INSERT INTO summaries (article_id, depth, status, content, ai_generated, created_at, updated_at)
                    VALUES (?, 'BALANCED', 'COMPLETED', ?, true, NOW(), NOW())
                    """, saved.getId(), text);
        }
    }

    private void insertGhostIssue(String keyword) {
        jdbcTemplate.update(
                "INSERT INTO issue_snapshot (clustering_method, delta, keywords, article_ids)"
                        + " VALUES ('CO_OCCURRENCE', NULL, ARRAY[?], ARRAY[999]::bigint[])",
                keyword);
    }

    private long dbIssueRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issue_snapshot", Long.class);
    }

    @Test
    @DisplayName("① 집계 커밋 후 evict: 캐시가 stale 서빙(활성) → 2차 집계 커밋 즉시 신규 반영")
    void afterCommitEvict_reflectsNewDataImmediately() {
        OffsetDateTime now = OffsetDateTime.now();
        // 1차: 토픽 A만 → 1 클러스터
        seedTopic("u/a", "한국은행 금리 인상 경제", now.minusHours(2));
        aggregationService.aggregate(); // 커밋 후 afterCommit evict(빈 캐시)

        assertThat(queryService.getIssues()).as("1차: 1 이슈").hasSize(1); // 캐시 적재(size 1)

        // 집계 없이 DB에 직접 1행 추가 → 캐시는 여전히 1을 서빙해야(캐시 활성 입증)
        insertGhostIssue("우회행");
        assertThat(dbIssueRows()).isEqualTo(2);
        assertThat(queryService.getIssues())
                .as("집계 없는 DB 변경 → 캐시가 stale(1) 서빙 = 캐시 활성")
                .hasSize(1);

        // 2차: 토픽 B 추가 → 집계 → re-derive(우회행 truncate로 소멸, A·B 2 클러스터) + afterCommit evict
        seedTopic("u/b", "부동산 대출 규제 정책", now.minusHours(2));
        aggregationService.aggregate();

        assertThat(queryService.getIssues())
                .as("2차 집계 커밋 직후 read = 신규 데이터(2 클러스터) 즉시 반영")
                .hasSize(2);
    }

    @Test
    @DisplayName("② 집계 TX 롤백: afterCommit 미발생 → 캐시 evict 안 됨(커밋 전 evict 창 없음)")
    void rollback_doesNotEvictCache() {
        OffsetDateTime now = OffsetDateTime.now();
        seedTopic("u/a", "한국은행 금리 인상 경제", now.minusHours(2));
        seedTopic("u/b", "부동산 대출 규제 정책", now.minusHours(2));
        aggregationService.aggregate(); // 2 클러스터, afterCommit evict

        assertThat(queryService.getIssues()).hasSize(2); // 캐시 적재(size 2)

        // DB를 캐시와 다르게 만든다(직접 1행 추가, 커밋됨)
        insertGhostIssue("유령이슈");
        assertThat(dbIssueRows()).isEqualTo(3);

        // 집계를 외부 TX 안에서 돌리고 강제 롤백 → aggregate의 afterCommit 동기화는 이 외부 TX에 바인딩됨
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.executeWithoutResult(status -> {
            aggregationService.aggregate(); // 작업 + afterCommit evict 등록(이 TX에)
            status.setRollbackOnly(); // 강제 롤백 → afterCommit 미발생
        });

        // 롤백으로 aggregate의 truncate/insert 무효 → DB는 (2 재현행+유령) 복원 = 3
        assertThat(dbIssueRows()).as("롤백으로 집계 작업 무효, 유령행 복원").isEqualTo(3);
        // 캐시는 evict 안 됨 → 여전히 stale(2)을 서빙. DB(3)와 다름 = afterCommit이 안 일어났다는 직접 증거.
        assertThat(queryService.getIssues())
                .as("롤백 시 evict 미발생 → 캐시 그대로(2), DB(3)와 불일치")
                .hasSize(2);
    }
}
