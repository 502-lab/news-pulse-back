package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.Article;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.TrendKeywordSlotRepository;
import com.newscurator.service.ArticleDetailService;
import com.newscurator.service.SavedArticleService;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 ① hidden 일관성(008 US3a, 실 PG). admin_hidden_at 설정 기사(H)가 모든 사용자향 읽기 경로에서
 * 제외됨을 each 단언 — visible 기사(V)는 포함, H는 제외(discriminating). 한 경로라도 필터 누락 시 단언 깨짐.
 *
 * <p>경로: 피드·검색·상세(일반 404/admin 포함)·트렌드추출·히트맵·슬롯UPSERT·북마크.
 * 트렌드는 article_keyword 행은 남되 집계 JOIN에서 제외(가역, plan OI-B).
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
class ArticleHiddenConsistencyIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_hidden_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleKeywordRepository articleKeywordRepository;
    @Autowired private TrendKeywordSlotRepository trendKeywordSlotRepository;
    @Autowired private ArticleDetailService articleDetailService;
    @Autowired private SavedArticleService savedArticleService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private long visibleId;
    private long hiddenId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_keyword, trend_keyword_slot, saved_articles, summaries,"
                        + " article_sources, articles, sources, accounts RESTART IDENTITY CASCADE");

        visibleId = article("u/visible", "금리 인상 속보", false);
        hiddenId = article("u/hidden", "금리 인하 속보", true); // ★ admin 숨김

        // 둘 다 같은 키워드 추출(article_keyword 행은 H도 보존됨)
        jdbcTemplate.update(
                "INSERT INTO article_keyword (article_id, term, created_at) VALUES (?, '금리', NOW())",
                visibleId);
        jdbcTemplate.update(
                "INSERT INTO article_keyword (article_id, term, created_at) VALUES (?, '금리', NOW())",
                hiddenId);

        // 사용자 + 둘 다 북마크
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'USER', 'EMAIL')",
                userId, "u-" + userId + "@test.com");
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id, saved_at) VALUES (?, ?, NOW())",
                userId, visibleId);
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id, saved_at) VALUES (?, ?, NOW())",
                userId, hiddenId);
    }

    /** COMPLETED·최근·feed_visible 기사 저장 후 hidden 여부 설정. */
    private long article(String url, String title, boolean hidden) {
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl(url).originalUrl(url).title(title)
                        .publishedAt(now.minusHours(1)).firstCollectedAt(now.minusHours(1))
                        .expiresAt(now.plusDays(90)).build();
        long id = articleRepository.save(a).getId();
        jdbcTemplate.update("UPDATE articles SET category_status='COMPLETED' WHERE id=?", id);
        if (hidden) {
            jdbcTemplate.update("UPDATE articles SET admin_hidden_at=NOW() WHERE id=?", id);
        }
        return id;
    }

    @Test
    @DisplayName("피드 — hidden 기사 미포함(visible만)")
    void feed_excludesHidden() {
        List<Long> ids =
                articleRepository
                        .findFeedPage(List.of(ProcessingStatus.COMPLETED), PageRequest.of(0, 10))
                        .stream()
                        .map(Article::getId)
                        .toList();
        assertThat(ids).contains(visibleId).doesNotContain(hiddenId);
    }

    @Test
    @DisplayName("검색 — hidden 기사 미포함(visible만)")
    void search_excludesHidden() {
        List<Long> ids =
                articleRepository.searchByQuery("금리", 10).stream()
                        .map(row -> ((Number) row[0]).longValue())
                        .toList();
        assertThat(ids).contains(visibleId).doesNotContain(hiddenId);
    }

    @Test
    @DisplayName("#9 상세 — 일반 사용자 hidden 404, admin(includeHidden) 포함")
    void detail_userHidden404_adminIncluded() {
        // visible: 일반 조회 OK
        assertThat(articleDetailService.getDetail(visibleId)).isNotNull();
        // hidden: 일반 사용자 404
        assertThatThrownBy(() -> articleDetailService.getDetail(hiddenId))
                .isInstanceOf(ArticleNotFoundException.class);
        // hidden: admin(includeHidden=true) 포함 조회 OK
        assertThat(articleDetailService.getDetail(hiddenId, true)).isNotNull();
    }

    @Test
    @DisplayName("트렌드 추출 — hidden 기사 키워드 제외(article_keyword 행은 보존, JOIN 제외 discriminating)")
    void trendExtraction_excludesHidden() {
        Instant window = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Long> ids =
                articleKeywordRepository.windowArticleKeywords(window).stream()
                        .map(row -> ((Number) row[0]).longValue())
                        .toList();
        assertThat(ids).contains(visibleId).doesNotContain(hiddenId);

        // ★ discriminating: article_keyword 행은 H도 남아있다(삭제 안 함) → 필터 없으면 잡힘
        Long hiddenKwRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM article_keyword WHERE article_id = ?", Long.class, hiddenId);
        assertThat(hiddenKwRows).as("hidden 기사 article_keyword 행은 보존(가역)").isEqualTo(1L);
    }

    @Test
    @DisplayName("히트맵 — hidden 제외(DISTINCT 기사 1건만)")
    void heatmap_excludesHidden() {
        Instant window = Instant.now().minus(24, ChronoUnit.HOURS);
        long totalArticles =
                articleKeywordRepository.heatmap(window).stream()
                        .mapToLong(row -> ((Number) row[2]).longValue())
                        .sum();
        assertThat(totalArticles).as("hidden 제외 → visible 1건만").isEqualTo(1L);
    }

    @Test
    @DisplayName("슬롯 UPSERT — hidden 제외('금리' article_count=1, 필터 없으면 2)")
    void slotUpsert_excludesHidden() {
        Instant window = Instant.now().minus(24, ChronoUnit.HOURS);
        new TransactionTemplate(txManager)
                .executeWithoutResult(s -> trendKeywordSlotRepository.upsertSlots(window));

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(article_count) FROM trend_keyword_slot WHERE term = '금리'",
                        Integer.class);
        assertThat(count).as("hidden 제외 → 금리 등장 기사 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("북마크 — hidden 기사 목록에서 제외(저장 행은 보존)")
    void bookmark_excludesHidden() {
        List<Long> ids =
                savedArticleService.list(userId, null, 10, false, null).articles().stream()
                        .map(item -> item.article().id())
                        .toList();
        assertThat(ids).contains(visibleId).doesNotContain(hiddenId);

        // 저장 행 자체는 2건 보존(노출만 차단)
        Long savedRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM saved_articles WHERE account_id = ?", Long.class, userId);
        assertThat(savedRows).isEqualTo(2L);
    }
}
