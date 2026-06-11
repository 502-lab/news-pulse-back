package com.newscurator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.newscurator.domain.Article;
import com.newscurator.domain.Summary;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.AiProcessingService;
import com.newscurator.service.ArticleDetailService;
import com.newscurator.service.SummaryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AiProcessingIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_ai_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.ai.delay-between-calls-ms", () -> "0");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", wireMock::baseUrl);
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", wireMock::baseUrl);
    }

    @Autowired private AiProcessingService aiProcessingService;
    @Autowired private ArticleDetailService articleDetailService;
    @Autowired private SummaryService summaryService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private SummaryRepository summaryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE summaries, article_sources, source_daily_usage, articles, sources"
                        + " RESTART IDENTITY CASCADE");
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private Article buildPendingArticle(String url) {
        return Article.builder()
                .normalizedUrl(url)
                .originalUrl(url)
                .title("Test Title " + url)
                .publishedAt(OffsetDateTime.now())
                .firstCollectedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(90))
                .build();
    }

    private static String geminiJson(String text) {
        return """
                {"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}
                """.formatted(text.replace("\"", "\\\""));
    }

    // ────────────────────────────── tests ──────────────────────────────

    @Test
    @DisplayName("성공: COMPLETED, BALANCED/BRIEF COMPLETED, DEEP NOT_GENERATED")
    void processArticle_success_completesWithAllSlots() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("분류하세요"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("POLITICS"))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("균형"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson(
                                "이것은 테스트 균형 요약입니다. 기사의 핵심 내용을 균형 있게 정리한"
                                        + " 결과물입니다. 충분히 긴 내용을 포함하여 200자 brief 트런케이션을"
                                        + " 검증합니다. 추가 내용도 포함합니다."))));

        Article article = articleRepository.save(buildPendingArticle("https://test.example.com/s1"));

        aiProcessingService.processArticle(article);

        Article result = articleRepository.findById(article.getId()).orElseThrow();
        assertThat(result.getCategoryStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(result.getCategory()).isEqualTo(Category.POLITICS);
        assertThat(result.getSummaryStatus()).isEqualTo(ProcessingStatus.COMPLETED);

        List<Summary> summaries = summaryRepository.findByArticleId(article.getId());
        assertThat(summaries).hasSize(3);

        Summary balanced = summaries.stream().filter(s -> s.getDepth() == SummaryDepth.BALANCED)
                .findFirst().orElseThrow();
        assertThat(balanced.getStatus()).isEqualTo(SummarySlotStatus.COMPLETED);
        assertThat(balanced.getContent()).isNotBlank();

        Summary brief = summaries.stream().filter(s -> s.getDepth() == SummaryDepth.BRIEF)
                .findFirst().orElseThrow();
        assertThat(brief.getStatus()).isEqualTo(SummarySlotStatus.COMPLETED);
        assertThat(brief.getContent()).isNotBlank();
        assertThat(brief.getContent().length()).isLessThanOrEqualTo(200);
        assertThat(brief.getContent())
                .isEqualTo(summaryService.truncateForBrief(balanced.getContent()));

        Summary deep = summaries.stream().filter(s -> s.getDepth() == SummaryDepth.DEEP)
                .findFirst().orElseThrow();
        assertThat(deep.getStatus()).isEqualTo(SummarySlotStatus.NOT_GENERATED);
    }

    @Test
    @DisplayName("알 수 없는 카테고리 응답 → OTHER 폴백")
    void processArticle_unknownCategory_fallsbackToOther() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("분류하세요"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("UNKNOWN_CATEGORY_XYZ"))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("균형"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("요약 내용입니다."))));

        Article article = articleRepository.save(buildPendingArticle("https://test.example.com/s2"));

        aiProcessingService.processArticle(article);

        Article result = articleRepository.findById(article.getId()).orElseThrow();
        assertThat(result.getCategoryStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(result.getCategory()).isEqualTo(Category.OTHER);
    }

    @Test
    @DisplayName("429 일시 오류: 배치 조기 중단, 두 기사 모두 PENDING 유지 + retry_count=0")
    void processBatch_429_keepsPendingAndDoesNotIncrementRetry() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(429)));

        articleRepository.save(buildPendingArticle("https://test.example.com/p1"));
        articleRepository.save(buildPendingArticle("https://test.example.com/p2"));

        aiProcessingService.processBatch();

        List<Article> articles = articleRepository.findAll();
        assertThat(articles).allSatisfy(a -> {
            assertThat(a.getCategoryStatus()).isEqualTo(ProcessingStatus.PENDING);
            assertThat(a.getSummaryStatus()).isEqualTo(ProcessingStatus.PENDING);
            assertThat(a.getCategoryRetryCount()).isZero();
            assertThat(a.getSummaryRetryCount()).isZero();
        });
    }

    @Test
    @DisplayName("400 하드 오류: retryLimit(3)회 후 category/summary FAILED")
    void processArticle_hardFailure400_boundsRetry_thenFails() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(400)));

        Article article = articleRepository.save(buildPendingArticle("https://test.example.com/f1"));

        for (int i = 0; i < 3; i++) {
            Article fresh = articleRepository.findById(article.getId()).orElseThrow();
            aiProcessingService.processArticle(fresh);
        }

        Article result = articleRepository.findById(article.getId()).orElseThrow();
        assertThat(result.getCategoryStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(result.getSummaryStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(result.getCategoryRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("lazy deep: 최초 getDetail 시 DEEP 생성, 재호출 시 재생성 없음")
    void getDetail_deepNotGenerated_triggersOnce() {
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("분류하세요"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("POLITICS"))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("균형"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("균형 잡힌 요약 내용입니다."))));
        wireMock.stubFor(post(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("심층"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiJson("심층 분석 요약입니다."))));

        Article article = articleRepository.save(buildPendingArticle("https://test.example.com/lazy"));
        aiProcessingService.processArticle(article);

        // DEEP 슬롯 NOT_GENERATED 확인
        Summary deepBefore = summaryRepository
                .findByArticleIdAndDepth(article.getId(), SummaryDepth.DEEP)
                .orElseThrow();
        assertThat(deepBefore.getStatus()).isEqualTo(SummarySlotStatus.NOT_GENERATED);

        // 최초 getDetail → DEEP 생성
        articleDetailService.getDetail(article.getId());

        Summary deepAfterFirst = summaryRepository
                .findByArticleIdAndDepth(article.getId(), SummaryDepth.DEEP)
                .orElseThrow();
        assertThat(deepAfterFirst.getStatus()).isEqualTo(SummarySlotStatus.COMPLETED);
        assertThat(deepAfterFirst.getContent()).isEqualTo("심층 분석 요약입니다.");

        // 재호출 시 WireMock deep 스텁이 다시 호출되지 않음을 검증
        articleDetailService.getDetail(article.getId());

        wireMock.verify(exactly(1), postRequestedFor(urlPathMatching(".*generateContent.*"))
                .withRequestBody(containing("심층")));
    }
}
