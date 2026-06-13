package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Article;
import com.newscurator.domain.SavedArticle;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.testutil.BigmPostgresImage;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
            "app.scheduler.enabled=false",
            "naver.clova.voice.api-key-id=test-clova-key-id",
            "naver.clova.voice.api-key=test-clova-key",
            "naver.clova.voice.base-url=http://localhost:9999",
            "cloud.aws.s3.bucket=test-bucket",
            "cloud.aws.cloudfront.domain=http://localhost",
            "cloud.aws.region=us-east-1"
        })
@Transactional
class SavedArticleListenableRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SavedArticleRepository savedArticleRepository;
    @Autowired ArticleRepository articleRepository;
    @Autowired TtsAudioRepository ttsAudioRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    // saved article DB ids (set per test via @BeforeEach)
    private Long idSaA, idSaB, idSaC, idSaD;
    // article ids used as TTS refId
    private Long artA, artB, artC, artD;

    private Article buildArticle(String urlSuffix) {
        OffsetDateTime now = OffsetDateTime.now();
        return Article.builder()
                .normalizedUrl("https://test.example.com/" + urlSuffix + "/" + UUID.randomUUID())
                .originalUrl("https://test.example.com/" + urlSuffix)
                .title("테스트 기사 " + urlSuffix)
                .publishedAt(now.minusHours(1))
                .firstCollectedAt(now)
                .expiresAt(now.plusDays(90))
                .build();
    }

    @BeforeEach
    void setUp() {
        // ── 1. Article 4건 — saved_articles.article_id FK 충족 ──────────────────
        Article artAEntity = articleRepository.saveAndFlush(buildArticle("a"));
        Article artBEntity = articleRepository.saveAndFlush(buildArticle("b"));
        Article artCEntity = articleRepository.saveAndFlush(buildArticle("c"));
        Article artDEntity = articleRepository.saveAndFlush(buildArticle("d"));
        artA = artAEntity.getId();
        artB = artBEntity.getId();
        artC = artCEntity.getId();
        artD = artDEntity.getId();

        // ── 2. SavedArticle 4건 ──────────────────────────────────────────────────
        SavedArticle saA =
                savedArticleRepository.saveAndFlush(
                        SavedArticle.builder().accountId(ACCOUNT_ID).articleId(artA).build());
        SavedArticle saB =
                savedArticleRepository.saveAndFlush(
                        SavedArticle.builder().accountId(ACCOUNT_ID).articleId(artB).build());
        SavedArticle saC =
                savedArticleRepository.saveAndFlush(
                        SavedArticle.builder().accountId(ACCOUNT_ID).articleId(artC).build());
        SavedArticle saD =
                savedArticleRepository.saveAndFlush(
                        SavedArticle.builder().accountId(ACCOUNT_ID).articleId(artD).build());
        idSaA = saA.getId();
        idSaB = saB.getId();
        idSaC = saC.getId();
        idSaD = saD.getId();

        // ── 3. Hibernate 1차 캐시 제거 → JdbcTemplate UPDATE가 덮어써지지 않도록 ──
        entityManager.flush();
        entityManager.clear();

        // ── 4. savedAt 명시 설정: A 가장 오래됨(-30s), D 가장 최근(now) ──────────
        Instant now = Instant.now();
        jdbcTemplate.update(
                "UPDATE saved_articles SET saved_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(30)), idSaA);
        jdbcTemplate.update(
                "UPDATE saved_articles SET saved_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(20)), idSaB);
        jdbcTemplate.update(
                "UPDATE saved_articles SET saved_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(10)), idSaC);
        jdbcTemplate.update(
                "UPDATE saved_articles SET saved_at = ? WHERE id = ?",
                Timestamp.from(now), idSaD);

        // ── 5. TTS rows 구성 ──────────────────────────────────────────────────────
        // A: READY, voice=harin
        TtsAudio ttsA =
                TtsAudio.builder()
                        .ownerType(TtsOwnerType.ARTICLE)
                        .refId(String.valueOf(artA))
                        .voiceId("harin")
                        .build();
        ttsA.complete("tts/" + artA + "/harin.mp3", null);
        ttsAudioRepository.saveAndFlush(ttsA);

        // B: PENDING (기본값), voice=harin
        ttsAudioRepository.saveAndFlush(
                TtsAudio.builder()
                        .ownerType(TtsOwnerType.ARTICLE)
                        .refId(String.valueOf(artB))
                        .voiceId("harin")
                        .build());

        // C: TTS 행 없음

        // D: READY, voice=junho
        TtsAudio ttsD =
                TtsAudio.builder()
                        .ownerType(TtsOwnerType.ARTICLE)
                        .refId(String.valueOf(artD))
                        .voiceId("junho")
                        .build();
        ttsD.complete("tts/" + artD + "/junho.mp3", null);
        ttsAudioRepository.saveAndFlush(ttsD);

        // native query 실행 전 Hibernate 캐시 재정리
        entityManager.flush();
        entityManager.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (1) voiceId=null → READY 이면 음성 무관 → A·D 반환, B(PENDING)·C(없음) 제외
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) voiceId=null: READY TTS 존재 기사만 — A(harin READY)·D(junho READY), B(PENDING)·C(없음) 제외")
    void findListenable_voiceIdNull_returnsAllReadyArticles() {
        List<SavedArticle> result =
                savedArticleRepository.findListenableOrderBySavedAtDesc(
                        ACCOUNT_ID, null, PageRequest.of(0, 10));

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(SavedArticle::getArticleId)
                .containsExactlyInAnyOrder(artA, artD)
                .doesNotContain(artB, artC);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (2) voiceId="harin" → harin READY만 → A만 반환, D(junho)·B(PENDING)·C(없음) 제외
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) voiceId=harin: harin READY TTS 기사만 — A만, D(junho READY)·B(PENDING)·C(없음) 제외")
    void findListenable_voiceIdHarin_returnsOnlyHarinReadyArticle() {
        List<SavedArticle> result =
                savedArticleRepository.findListenableOrderBySavedAtDesc(
                        ACCOUNT_ID, "harin", PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getArticleId()).isEqualTo(artA);
        assertThat(result)
                .extracting(SavedArticle::getArticleId)
                .doesNotContain(artB, artC, artD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (3) 정렬: savedAt DESC → D(가장 최근)가 먼저, A(가장 오래됨)가 나중
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) savedAt DESC 정렬: D(최신 saved_at)가 [0], A(가장 오래된 saved_at)가 [1]")
    void findListenable_voiceIdNull_orderedBySavedAtDesc() {
        List<SavedArticle> result =
                savedArticleRepository.findListenableOrderBySavedAtDesc(
                        ACCOUNT_ID, null, PageRequest.of(0, 10));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getArticleId()).isEqualTo(artD);
        assertThat(result.get(1).getArticleId()).isEqualTo(artA);
        assertThat(result.get(0).getSavedAt()).isAfter(result.get(1).getSavedAt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (4) 회귀: listenable 비적용 경로 — 기존 메서드는 4건 모두 반환
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) 회귀: findByAccountIdOrderBySavedAtDesc는 listenable 무관 A·B·C·D 모두 반환")
    void findByAccountId_noListenableFilter_returnsAllFour() {
        List<SavedArticle> result =
                savedArticleRepository.findByAccountIdOrderBySavedAtDesc(
                        ACCOUNT_ID, PageRequest.of(0, 10));

        assertThat(result).hasSize(4);
        assertThat(result)
                .extracting(SavedArticle::getArticleId)
                .containsExactlyInAnyOrder(artA, artB, artC, artD);
    }
}
