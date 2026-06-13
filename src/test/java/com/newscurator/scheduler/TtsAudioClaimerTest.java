package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.repository.TtsAudioRepository;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
            "app.scheduler.enabled=false",
            "cloud.aws.s3.bucket=test-bucket",
            "cloud.aws.cloudfront.domain=http://localhost",
            "cloud.aws.region=us-east-1"
        })
class TtsAudioClaimerTest {

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
    TtsAudioClaimer ttsAudioClaimer;

    @Autowired
    TtsAudioRepository ttsAudioRepository;

    @AfterEach
    void cleanup() {
        ttsAudioRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────
    // claimBatch TX 정합성: PENDING → PROCESSING 마킹 후 커밋,
    // 재클레임 시 동일 행 재처리 없음
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING 3건 → claimBatch(10) → 3건 반환·전부 PROCESSING, 재클레임 → 빈 리스트")
    void claimBatch_pending3_allBecomesProcessing_secondCallReturnsEmpty() {
        // PENDING 3건 저장
        for (int i = 1; i <= 3; i++) {
            ttsAudioRepository.save(TtsAudio.builder()
                    .ownerType(TtsOwnerType.ARTICLE)
                    .refId(String.valueOf(i * 10))
                    .voiceId("Seoyeon")
                    .build());
        }

        // 첫 번째 클레임: 3건 반환, 전부 PROCESSING
        List<TtsAudio> first = ttsAudioClaimer.claimBatch(10);

        assertThat(first).hasSize(3);
        assertThat(first).extracting(TtsAudio::getStatus)
                .containsOnly(TtsStatus.PROCESSING);

        // DB에서 직접 확인: PENDING이 0건 (FOR UPDATE SKIP LOCKED 커밋 이후)
        List<TtsAudio> allRows = ttsAudioRepository.findAll();
        assertThat(allRows).hasSize(3);
        assertThat(allRows).extracting(TtsAudio::getStatus)
                .containsOnly(TtsStatus.PROCESSING);

        // 두 번째 클레임: PENDING 없음 → 빈 리스트 (이중 처리·이중 과금 없음)
        List<TtsAudio> second = ttsAudioClaimer.claimBatch(10);

        assertThat(second).isEmpty();
    }
}
