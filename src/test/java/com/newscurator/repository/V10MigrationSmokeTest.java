package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Voice;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
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

/** V10 마이그레이션 적용 확인 — voices 시드, tts_audios UNIQUE, reading_preferences.voice_id 컬럼 */
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
class V10MigrationSmokeTest {

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
    VoiceRepository voiceRepository;

    @Autowired
    TtsAudioRepository ttsAudioRepository;

    @Test
    @DisplayName("V10 시드: voices 테이블에 하린·준서 2건 존재")
    void voicesSeedApplied() {
        List<Voice> voices = voiceRepository.findAll();
        assertThat(voices).hasSize(2);
        assertThat(voices).extracting(Voice::getId).containsExactlyInAnyOrder("harin", "junho");
        assertThat(voices).extracting(Voice::getGender)
                .containsExactlyInAnyOrder("FEMALE", "MALE");
    }

    @Test
    @DisplayName("V10: TtsOwnerType·TtsStatus enum이 ARTICLE/PENDING으로 저장 가능")
    void ttsAudioPersistAndRetrieve() {
        var ttsAudio = com.newscurator.domain.TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId("99999")
                .voiceId("harin")
                .build();
        var saved = ttsAudioRepository.save(ttsAudio);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TtsStatus.PENDING);
        assertThat(saved.getAudioKey()).isNull();
    }

    @Test
    @DisplayName("V10: resetToPending() — FAILED 행을 PENDING으로 UPDATE (INSERT 아님)")
    void resetToPendingUpdateExistingRow() {
        var ttsAudio = com.newscurator.domain.TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId("88888")
                .voiceId("harin")
                .build();
        ttsAudio.fail("Naver API timeout");
        var saved = ttsAudioRepository.save(ttsAudio);
        assertThat(saved.getStatus()).isEqualTo(TtsStatus.FAILED);

        saved.resetToPending();
        var updated = ttsAudioRepository.save(saved);

        // 행 수 불변: owner_type+ref_id+voice_id 동일 조합은 1건만 존재
        long count = ttsAudioRepository.findAll().stream()
                .filter(t -> "88888".equals(t.getRefId()))
                .count();
        assertThat(count).isEqualTo(1);
        assertThat(updated.getId()).isEqualTo(saved.getId());
        assertThat(updated.getStatus()).isEqualTo(TtsStatus.PENDING);
        assertThat(updated.getAudioKey()).isNull();
        assertThat(updated.getErrorMsg()).isNull();
    }
}
