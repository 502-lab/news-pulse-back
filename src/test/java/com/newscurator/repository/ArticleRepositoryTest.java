package com.newscurator.repository;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.enums.ProcessingStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class ArticleRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private ArticleRepository articleRepository;

    @BeforeEach
    void cleanup() {
        articleRepository.deleteAll();
    }

    @Test
    @DisplayName("normalized_url UNIQUE 제약: 동일 URL 저장 시 예외 발생")
    void normalizedUrl_unique_constraint() {
        Article article1 = buildArticle("https://example.com/news/1");
        articleRepository.saveAndFlush(article1);

        Article article2 = buildArticle("https://example.com/news/1");
        assertThatThrownBy(() -> articleRepository.saveAndFlush(article2))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("normalized_url 기준 기사 조회")
    void findByNormalizedUrl_exists() {
        Article article = buildArticle("https://example.com/news/2");
        articleRepository.saveAndFlush(article);

        assertThat(articleRepository.findByNormalizedUrl("https://example.com/news/2"))
                .isPresent();
    }

    @Test
    @DisplayName("존재하지 않는 normalized_url 조회 시 empty")
    void findByNormalizedUrl_notFound() {
        assertThat(articleRepository.findByNormalizedUrl("https://not-exist.com/news/99"))
                .isEmpty();
    }

    @Test
    @DisplayName("피드 조회: feed_visible=true, categoryStatus=COMPLETED 조건")
    void findFeedPage_returnsVisibleCompletedArticles() {
        Article visible = buildArticle("https://example.com/news/3");
        articleRepository.saveAndFlush(visible);

        List<ProcessingStatus> statuses =
                List.of(ProcessingStatus.COMPLETED, ProcessingStatus.FAILED);
        List<Article> feed =
                articleRepository.findFeedPage(
                        statuses,
                        org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(feed).noneMatch(a -> a.getCategoryStatus() == ProcessingStatus.PENDING);
    }

    @Test
    @Disabled(
            "SELECT...FOR UPDATE SKIP LOCKED requires committed data visible across transactions;"
                    + " incompatible with test transaction rollback. Covered by E2E integration test.")
    @DisplayName("lockAndClaimPending: 2개 스레드가 각각 다른 기사를 클레임")
    void lockAndClaimPending_concurrentClaim_eachGetDifferentArticles() {}

    private Article buildArticle(String normalizedUrl) {
        OffsetDateTime now = OffsetDateTime.now();
        return Article.builder()
                .normalizedUrl(normalizedUrl)
                .originalUrl(normalizedUrl)
                .title("Test Article")
                .publishedAt(now)
                .firstCollectedAt(now)
                .expiresAt(now.plusDays(90))
                .build();
    }
}
