package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.within;

import com.newscurator.domain.Article;
import com.newscurator.dto.response.BiasSpectrumResponse;
import com.newscurator.repository.ArticleRepository;
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
 * T036: 편향 스펙트럼 집계 테스트 (Testcontainers 실 PostgreSQL).
 * 정수 inclusive 버킷 경계(−34→진보 / −33→중립 / +33→중립 / +34→보수) + % 합산 100% + 빈 응답.
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
class BiasSpectrumTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_spectrum_it")
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
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE bias_analysis, summaries, article_sources, source_daily_usage,"
                        + " articles, sources RESTART IDENTITY CASCADE");
    }

    private void addDone(String url, int value) {
        Article a = articleRepository.save(Article.builder()
                .normalizedUrl(url).originalUrl(url).title("t " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90)).build());
        jdbcTemplate.update("""
                INSERT INTO bias_analysis
                    (article_id, status, value, attempt_count, next_retry_at, analyzed_at, created_at, updated_at)
                VALUES (?, 'DONE', ?, 0, NOW(), NOW(), NOW(), NOW())
                """, a.getId(), value);
    }

    @Test
    @DisplayName("정수 inclusive 경계: -34→진보 / -33→중립 / +33→중립 / +34→보수, % 합산 100%")
    void integerInclusiveBoundaries() {
        addDone("https://x/lib34", -34);   // 진보[-100,-34]
        addDone("https://x/neu33n", -33);  // 중립[-33,+33]
        addDone("https://x/neu33p", 33);   // 중립[-33,+33]
        addDone("https://x/con34", 34);    // 보수[+34,+100]

        BiasSpectrumResponse r = biasAnalysisService.getSpectrum();

        assertThat(r.totalCount()).isEqualTo(4);
        // -34 1건 → 진보 25%
        assertThat(r.liberalPercent()).isEqualTo(25.0);
        // -33, +33 2건 → 중립 50%
        assertThat(r.neutralPercent()).isEqualTo(50.0);
        // +34 1건 → 보수 25%
        assertThat(r.conservativePercent()).isEqualTo(25.0);
        // 합산 100%
        assertThat(r.liberalPercent() + r.neutralPercent() + r.conservativePercent())
                .isEqualTo(100.0);
        // 가중평균 (-34-33+33+34)/4 = 0.0
        assertThat(r.weightedAverage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("극단값 분류: -100→진보, +100→보수, 0→중립")
    void extremesAndZero() {
        addDone("https://x/min", -100);
        addDone("https://x/zero", 0);
        addDone("https://x/max", 100);

        BiasSpectrumResponse r = biasAnalysisService.getSpectrum();

        assertThat(r.totalCount()).isEqualTo(3);
        // SQL numeric→double 변환 오차 허용 (33.33…)
        assertThat(r.liberalPercent()).isCloseTo(100.0 / 3, within(0.01));
        assertThat(r.neutralPercent()).isCloseTo(100.0 / 3, within(0.01));
        assertThat(r.conservativePercent()).isCloseTo(100.0 / 3, within(0.01));
        assertThat(r.liberalPercent() + r.neutralPercent() + r.conservativePercent())
                .isCloseTo(100.0, within(0.01));
    }

    @Test
    @DisplayName("분석완료 0건: 모든 집계 값 null, totalCount=0 (division-by-zero 없음)")
    void empty_allNull() {
        BiasSpectrumResponse r = biasAnalysisService.getSpectrum();

        assertThat(r.totalCount()).isZero();
        assertThat(r.weightedAverage()).isNull();
        assertThat(r.liberalPercent()).isNull();
        assertThat(r.neutralPercent()).isNull();
        assertThat(r.conservativePercent()).isNull();
    }
}
