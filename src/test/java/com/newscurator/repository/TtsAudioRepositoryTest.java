package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class TtsAudioRepositoryTest {

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

    @Autowired
    TtsAudioRepository ttsAudioRepository;

    private TtsAudio buildPending(String refId, String voiceId) {
        return TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(refId)
                .voiceId(voiceId)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // (1) UNIQUE 제약 위반
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 동일 (ownerType, refId, voiceId) 두 번 save → DataIntegrityViolationException")
    void save_duplicateOwnerTypeRefIdVoiceId_throwsDataIntegrityViolationException() {
        TtsAudio first = buildPending("42", "harin");
        ttsAudioRepository.saveAndFlush(first);

        TtsAudio second = buildPending("42", "harin");

        assertThatThrownBy(() -> ttsAudioRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────────────────────
    // (2) findPendingWithLock: PENDING만 반환
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) findPendingWithLock: PENDING 2건·PROCESSING 1건·READY 1건 → PENDING 2건만 반환")
    void findPendingWithLock_returnsOnlyPendingItems() {
        TtsAudio pending1 = buildPending("10", "harin");
        TtsAudio pending2 = buildPending("11", "junho");
        TtsAudio processing = buildPending("12", "harin");
        TtsAudio ready = buildPending("13", "junho");

        ttsAudioRepository.saveAndFlush(pending1);
        ttsAudioRepository.saveAndFlush(pending2);
        processing.markProcessing();
        ttsAudioRepository.saveAndFlush(processing);
        ready.complete("tts/article/13/junho.mp3", null);
        ttsAudioRepository.saveAndFlush(ready);

        List<TtsAudio> result = ttsAudioRepository.findPendingWithLock(10);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TtsAudio::getStatus)
                .containsOnly(TtsStatus.PENDING);
        // PROCESSING("12")·READY("13")이 결과에 없음을 refId로 직접 증명
        assertThat(result).extracting(TtsAudio::getRefId)
                .containsExactlyInAnyOrder("10", "11")
                .doesNotContain("12", "13");
    }

    // ─────────────────────────────────────────────────────────
    // (3) FAILED → resetToPending(): UPDATE (행 수 불변, 동일 id)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) FAILED resetToPending(): 행 수 불변(1), 동일 id, status=PENDING, audioKey=null")
    void resetToPending_failedTtsAudio_updatesInPlaceWithoutNewInsert() {
        TtsAudio tts = buildPending("20", "harin");
        tts = ttsAudioRepository.saveAndFlush(tts);
        tts.fail("처리 실패");
        tts = ttsAudioRepository.saveAndFlush(tts);

        UUID originalId = tts.getId();
        assertThat(tts.getStatus()).isEqualTo(TtsStatus.FAILED);

        // resetToPending + save
        tts.resetToPending();
        ttsAudioRepository.saveAndFlush(tts);

        // 행 수 불변 (INSERT 아닌 UPDATE)
        long count = ttsAudioRepository.countByOwnerTypeAndRefIdAndVoiceId(
                TtsOwnerType.ARTICLE, "20", "harin");
        assertThat(count).isEqualTo(1);

        // 동일 id, status=PENDING, audioKey=null
        TtsAudio reloaded = ttsAudioRepository.findById(originalId).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(originalId);
        assertThat(reloaded.getStatus()).isEqualTo(TtsStatus.PENDING);
        assertThat(reloaded.getAudioKey()).isNull();
        assertThat(reloaded.getErrorMsg()).isNull();
    }
}
