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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T041: 이슈 재산출(re-derive, OI-4) 통합 테스트 (실 PostgreSQL + 실 Nori).
 *
 * <p>1차 집계 → issue_snapshot N행 생성 → (스테일 행 주입) → 2차 집계 후 TRUNCATE+INSERT로
 * 깨끗이 교체됨을 검증한다. cross-run 안정 ID 없음(RESTART IDENTITY로 id 재시작) 확인.
 * delta는 멤버 키워드 WoW 집계로 산출됨을 함께 확인한다.
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
class TrendIssueRederiveIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_issue_it")
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
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, issue_snapshot, summaries,"
                        + " article_sources, source_daily_usage, articles, sources RESTART IDENTITY CASCADE");
    }

    private void completedArticle(String url, String title, String balanced, OffsetDateTime collectedAt) {
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
    }

    private long issueRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issue_snapshot", Long.class);
    }

    @Test
    @DisplayName("재산출: 1차→N행, 2차 TRUNCATE+INSERT로 깨끗이 교체(스테일 0, 안정 ID 없음), delta는 멤버 WoW 집계")
    void rederive_truncateInsert_cleanReplacement() {
        // 금리·인상·경제가 3개 기사에 함께 등장 → co-occurrence 클러스터 1개 형성
        OffsetDateTime now = OffsetDateTime.now();
        completedArticle("u/1", "정부 금리 인상", "한국은행 금리 인상 발표 경제 영향", now.minusHours(2));
        completedArticle("u/2", "금리 인상 기조", "시장 금리 인상 지속 경제 전망", now.minusHours(2));
        completedArticle("u/3", "추가 금리 인상", "연속 금리 인상 단행 경제 충격", now.minusHours(2));

        // ── 1차 집계 ──
        service.aggregate();

        long n1 = issueRows();
        assertThat(n1).as("1차 집계로 issue_snapshot에 이슈가 생성됨").isGreaterThan(0);
        Long maxId1 = jdbcTemplate.queryForObject("SELECT MAX(id) FROM issue_snapshot", Long.class);
        // 클러스터에 금리/인상/경제 대표 키워드가 포함됨
        Long geumriIssues = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE '금리' = ANY(keywords)", Long.class);
        assertThat(geumriIssues).isGreaterThan(0L);

        // ── 스테일 행 주입: 이전 run의 잔존 행을 흉내 ──
        jdbcTemplate.update(
                "INSERT INTO issue_snapshot (clustering_method, delta, keywords, article_ids)"
                        + " VALUES ('CO_OCCURRENCE', 999.99, ARRAY['STALE_GHOST'], ARRAY[999]::bigint[])");
        assertThat(issueRows()).isEqualTo(n1 + 1);

        // ── 2차 집계: TRUNCATE+INSERT 단일 TX로 전량 교체 ──
        service.aggregate();

        // 1) 스테일 행 0 — TRUNCATE가 이전 데이터(주입 행 포함)를 전부 제거했음을 직접 증명
        Long staleRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE 'STALE_GHOST' = ANY(keywords)", Long.class);
        assertThat(staleRows).as("TRUNCATE로 스테일 행 완전 제거").isZero();

        // 2) 누적 없이 동일 N행으로 깨끗이 교체(2N 아님)
        long n2 = issueRows();
        assertThat(n2).as("재집계는 누적이 아니라 전량 교체").isEqualTo(n1);

        // 3) 안정 ID 없음 — RESTART IDENTITY로 id가 1부터 다시 시작(1차 maxId 이어가지 않음)
        Long minId2 = jdbcTemplate.queryForObject("SELECT MIN(id) FROM issue_snapshot", Long.class);
        Long maxId2 = jdbcTemplate.queryForObject("SELECT MAX(id) FROM issue_snapshot", Long.class);
        assertThat(minId2).as("TRUNCATE ... RESTART IDENTITY → id 재시작").isEqualTo(1L);
        assertThat(maxId2).as("2차 id가 1차 maxId를 누적·이어받지 않음(안정 ID 없음)").isEqualTo(n2);
        assertThat(maxId1).isNotNull();

        // 4) delta 컬럼은 멤버 키워드 WoW 집계 결과로 채워짐(여기선 prev 슬롯 없음 → 모두 신규 → delta NULL)
        //    핵심: delta가 클러스터러의 averageDelta 산출 경로를 통해 들어온다는 것.
        Long nonNullDelta = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE delta IS NOT NULL", Long.class);
        Long nullDelta = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM issue_snapshot WHERE delta IS NULL", Long.class);
        // prev 주 데이터가 없으므로 멤버 키워드 WoW delta는 전부 null → 클러스터 delta도 null
        assertThat(nonNullDelta + nullDelta).isEqualTo(n2);
        assertThat(nullDelta).as("prev 슬롯 부재 → 멤버 WoW delta 전부 null → 클러스터 delta null").isEqualTo(n2);
    }
}
