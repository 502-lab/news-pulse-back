package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
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

/** 010 T012 — InsightAggregationRepository(실 PG): 읽은수 숨김제외 + 추천 후보 제외 로직(조회·저장·숨김·14일). */
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
class InsightAggregationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_insight_repo_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private InsightAggregationRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID acc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE article_event, saved_articles, articles, accounts RESTART IDENTITY CASCADE");
        acc =
                jdbcTemplate.queryForObject(
                        "INSERT INTO accounts (email, role, status, signup_type)"
                            + " VALUES ('r@repo.local','USER','ACTIVE','EMAIL') RETURNING id",
                        UUID.class);
    }

    private long article(String url, boolean hidden, int daysAgo) {
        OffsetDateTime pub = OffsetDateTime.now().minusDays(daysAgo);
        jdbcTemplate.update(
                "INSERT INTO articles (normalized_url, original_url, title, category, published_at,"
                    + " first_collected_at, category_status, summary_status, expires_at, feed_visible,"
                    + " admin_hidden_at)"
                    + " VALUES (?, ?, '제목', 'IT', ?, ?, 'COMPLETED', 'COMPLETED', ?, true, ?)",
                url, url, pub, pub, pub.plusDays(90), hidden ? OffsetDateTime.now() : null);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM articles WHERE normalized_url = ?", Long.class, url);
    }

    private void view(long id) {
        jdbcTemplate.update(
                "INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)"
                        + " VALUES (?, ?, 'VIEW', 'SERVER', NOW())",
                acc, id);
    }

    @Test
    @DisplayName("읽은수 — 숨김 기사 조회는 제외")
    void countReadArticles_excludesHidden() {
        long visible = article("v", false, 0);
        long hidden = article("h", true, 0);
        view(visible);
        view(hidden);
        assertThat(repository.countReadArticles(acc)).isEqualTo(1L); // hidden 제외
    }

    @Test
    @DisplayName("추천 후보 — 조회·저장·숨김·14일 밖 제외")
    void recommendationCandidates_excludes() {
        long fresh = article("fresh", false, 1); // 후보 ✓
        long viewed = article("viewed", false, 1); // 조회 → 제외
        long saved = article("saved", false, 1); // 저장 → 제외
        long hidden = article("hidden", true, 1); // 숨김 → 제외
        long old = article("old", false, 30); // 14일 밖 → 제외

        view(viewed);
        jdbcTemplate.update(
                "INSERT INTO saved_articles (account_id, article_id) VALUES (?, ?)", acc, saved);

        List<Article> candidates = repository.findRecommendationCandidates(acc, 14, 50);
        List<Long> ids = candidates.stream().map(Article::getId).toList();

        assertThat(ids).containsExactly(fresh);
        assertThat(ids).doesNotContain(viewed, saved, hidden, old);
    }
}
