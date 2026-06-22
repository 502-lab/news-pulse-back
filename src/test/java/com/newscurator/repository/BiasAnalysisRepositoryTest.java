package com.newscurator.repository;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.domain.Article;
import com.newscurator.domain.BiasAnalysis;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.newscurator.testutil.BigmPostgresImage;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T010: BiasAnalysisRepository 통합 테스트 (Testcontainers 실 PostgreSQL). */
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
class BiasAnalysisRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_bias_repo_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private BiasAnalysisRepository biasAnalysisRepository;
    @Autowired private ArticleRepository articleRepository;

    @BeforeEach
    void clean() {
        biasAnalysisRepository.deleteAll();
        articleRepository.deleteAll();
    }

    private Article saveArticle(String url) {
        return articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build());
    }

    @Test
    @DisplayName("UNIQUE(article_id) 멱등: 동일 기사 두 행 저장 시 예외")
    void uniqueArticleId_throws() {
        Article a = saveArticle("https://ex.com/u");
        biasAnalysisRepository.saveAndFlush(BiasAnalysis.builder().articleId(a.getId()).build());

        assertThatThrownBy(() -> biasAnalysisRepository.saveAndFlush(
                BiasAnalysis.builder().articleId(a.getId()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("lockAndClaimPending: PENDING 행을 batch 한도까지 조회")
    void lockAndClaimPending_returnsPending() {
        Article a1 = saveArticle("https://ex.com/p1");
        Article a2 = saveArticle("https://ex.com/p2");
        biasAnalysisRepository.save(BiasAnalysis.builder().articleId(a1.getId()).build());
        biasAnalysisRepository.save(BiasAnalysis.builder().articleId(a2.getId()).build());

        List<BiasAnalysis> claimed = biasAnalysisRepository.lockAndClaimPending(10);

        assertThat(claimed).hasSize(2);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("backfillPending ON CONFLICT: 미존재 기사만 생성, 재실행 시 0")
    void backfillPending_idempotent() {
        Article a1 = saveArticle("https://ex.com/bf1");
        Article a2 = saveArticle("https://ex.com/bf2");
        // a1은 이미 bias 행 존재
        biasAnalysisRepository.save(BiasAnalysis.builder().articleId(a1.getId()).build());

        int firstCreated = biasAnalysisRepository.backfillPending();
        int secondCreated = biasAnalysisRepository.backfillPending();

        assertThat(firstCreated).isEqualTo(1); // a2만 신규
        assertThat(secondCreated).isZero();
        assertThat(biasAnalysisRepository.findByArticleId(a2.getId())).isPresent();
    }
}
