package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 크라운주얼 ② hidden↔만료 독립(008 T078, 실 PG). admin_hidden_at(가역 숨김)과 feed_visible 만료(물리삭제)가
 * 서로 독립임을 검증 — research D1(feed_visible 재활용 금지 근거).
 *
 * <ul>
 *   <li>admin 숨김은 feed_visible을 건드리지 않는다(만료 트리거 아님).</li>
 *   <li>★ admin-hidden(미만료) 기사는 만료 물리삭제 후보가 아니다(feed_visible=true라 findArticlesToDelete 제외).</li>
 *   <li>만료(feed_visible=false)는 admin_hidden_at을 건드리지 않는다.</li>
 *   <li>unhide 가능(admin_hidden_at=null, feed_visible 불변).</li>
 * </ul>
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
class HiddenExpiryIndependenceIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_hidexp_it")
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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private int hideExpired() {
        return new TransactionTemplate(txManager)
                .execute(s -> articleRepository.hideExpiredArticles(OffsetDateTime.now()));
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE articles RESTART IDENTITY CASCADE");
    }

    private long save(String url, OffsetDateTime expiresAt, boolean adminHidden) {
        OffsetDateTime now = OffsetDateTime.now();
        Article a =
                Article.builder()
                        .normalizedUrl(url).originalUrl(url).title("기사 " + url)
                        .publishedAt(now.minusHours(1)).firstCollectedAt(now.minusHours(1))
                        .expiresAt(expiresAt).build();
        if (adminHidden) {
            a.hideByAdmin(now);
        }
        return articleRepository.save(a).getId();
    }

    private boolean feedVisible(long id) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        "SELECT feed_visible FROM articles WHERE id = ?", Boolean.class, id));
    }

    private boolean adminHidden(long id) {
        Object v =
                jdbcTemplate.queryForObject(
                        "SELECT admin_hidden_at FROM articles WHERE id = ?", Object.class, id);
        return v != null;
    }

    @Test
    @DisplayName("admin 숨김은 feed_visible 불변 + 미만료 hidden 기사는 물리삭제 후보 아님")
    void adminHide_doesNotAffectExpiryDeletion() {
        // admin-hidden, 미만료(expiresAt 미래)
        long hidden = save("u/hidden", OffsetDateTime.now().plusDays(90), true);

        assertThat(adminHidden(hidden)).isTrue();
        assertThat(feedVisible(hidden)).as("admin 숨김은 feed_visible 건드리지 않음").isTrue();

        // 만료 1단계: 미만료라 영향 없음
        int affected = hideExpired();
        assertThat(affected).isZero();
        assertThat(feedVisible(hidden)).isTrue();

        // ★ 물리삭제 후보 조회(grace 충분히 미래) → feed_visible=true라 hidden 기사는 제외
        List<Long> deleteCandidates =
                articleRepository.findArticlesToDelete(OffsetDateTime.now().plusDays(1)).stream()
                        .map(Article::getId)
                        .toList();
        assertThat(deleteCandidates)
                .as("admin-hidden(미만료)은 만료 물리삭제 후보 아님")
                .doesNotContain(hidden);
    }

    @Test
    @DisplayName("만료(feed_visible=false)는 admin_hidden_at 불변")
    void expiry_doesNotSetAdminHidden() {
        // 만료, admin 숨김 아님
        long expired = save("u/expired", OffsetDateTime.now().minusDays(1), false);

        int affected = hideExpired();
        assertThat(affected).isEqualTo(1);
        assertThat(feedVisible(expired)).as("만료 → feed_visible=false").isFalse();
        assertThat(adminHidden(expired)).as("만료는 admin_hidden_at 건드리지 않음").isFalse();
    }

    @Test
    @DisplayName("unhide 가능 — admin_hidden_at=null, feed_visible 불변")
    void unhide_reversible() {
        long id = save("u/h2", OffsetDateTime.now().plusDays(90), true);
        Article a = articleRepository.findById(id).orElseThrow();

        a.unhideByAdmin();
        articleRepository.save(a);

        assertThat(adminHidden(id)).as("unhide → admin_hidden_at null").isFalse();
        assertThat(feedVisible(id)).isTrue();
    }
}
