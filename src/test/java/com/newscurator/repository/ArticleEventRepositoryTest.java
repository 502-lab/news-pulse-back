package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.repository.ArticleEventRepository.ArticleViewHistoryRow;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 009 T009 — ArticleEventRepository(실 PG): 조건부 INSERT 영향행수·distinct·history.
 *
 * <p>insert는 production(REQUIRES_NEW)처럼 별개 TX로 호출한다(TransactionTemplate) — 클래스 @Transactional로
 * 묶으면 PG NOW()가 TX 시작 시각으로 고정돼 모든 행이 같은 시각이 되어 디바운스/이력 시각 검증이 무의미해짐.
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
class ArticleEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_article_event_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private ArticleEventRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private UUID account;
    private long article1;
    private long article2;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, article_sources, summaries, articles, accounts"
                        + " RESTART IDENTITY CASCADE");
        account = account("reader@test.com");
        article1 = article("u/1");
        article2 = article("u/2");
    }

    /** 별개 TX로 조건부 INSERT(production REQUIRES_NEW 모사) → 영향행수. */
    private int insertView(UUID acc, long art) {
        return tx.execute(s -> repository.insertViewDebounced(acc, art));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, status, signup_type)"
                        + " VALUES (?, ?, 'USER', 'ACTIVE', 'EMAIL')",
                id, email);
        return id;
    }

    private long article(String url) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, published_at,"
                        + " first_collected_at, category_status, summary_status, expires_at, feed_visible)"
                        + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', ?, true)",
                url, url, "title " + url, now, now, now.plusDays(90));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    @Test
    @DisplayName("조건부 INSERT — 신규=1, 30분내 중복=0, 30분 경과 후=1")
    void insertViewDebounced_affectedRows() {
        assertThat(insertView(account, article1)).isEqualTo(1); // 신규
        assertThat(insertView(account, article1)).isEqualTo(0); // 30분내 중복 skip

        // 기존 행을 31분 전으로 backdate → 윈도우 밖
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '31 minutes'"
                        + " WHERE account_id = ? AND article_id = ?",
                account, article1);
        assertThat(insertView(account, article1)).isEqualTo(1); // 경과 후 신규

        // 다른 기사는 독립
        assertThat(insertView(account, article2)).isEqualTo(1);

        Long rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM article_event WHERE account_id = ?", Long.class, account);
        assertThat(rows).isEqualTo(3L);
    }

    @Test
    @DisplayName("읽은수 = distinct article (같은 기사 다회여도 1)")
    void countDistinct() {
        insertView(account, article1);
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '31 minutes'"
                        + " WHERE account_id = ? AND article_id = ?",
                account, article1);
        insertView(account, article1); // 같은 기사 2번째
        insertView(account, article2);

        assertThat(repository.countDistinctArticlesByAccount(account)).isEqualTo(2L);
    }

    @Test
    @DisplayName("history — article 최신 1건, occurred_at DESC, 커서")
    void findHistory_distinctLatestDesc() {
        // article1: 40분 전 + 최신(재조회) → MAX=최신. article2: 20분 전.
        insertView(account, article1);
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '40 minutes'"
                        + " WHERE account_id = ? AND article_id = ?",
                account, article1);
        insertView(account, article2);
        jdbcTemplate.update(
                "UPDATE article_event SET occurred_at = NOW() - INTERVAL '20 minutes'"
                        + " WHERE account_id = ? AND article_id = ?",
                account, article2);
        insertView(account, article1); // article1 재조회(가장 최신)

        List<ArticleViewHistoryRow> page = repository.findHistory(account, null, 20);
        // 같은 기사 다회 조회여도 article 기준 1건씩 → 2건
        assertThat(page).hasSize(2);
        // 최신순: article1 재조회가 가장 최근
        assertThat(page.get(0).getArticleId()).isEqualTo(article1);
        assertThat(page.get(0).getTitle()).isNotNull();

        // 커서: 첫 항목 시각 미만 → 그 다음 항목만
        Instant cursor = page.get(0).getLastViewedAt();
        List<ArticleViewHistoryRow> next = repository.findHistory(account, cursor, 20);
        assertThat(next).hasSize(1);
        assertThat(next.get(0).getArticleId()).isEqualTo(article2);
    }
}
