package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.Voice;
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

/** V10 마이그레이션 시드 적용 후 VoiceRepository.findAll() 검증 */
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
class VoiceRepositoryTest {

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

    @Test
    @DisplayName("V10 시드 후 findAll() → 서연(Seoyeon) 1건 반환 (previewUrl=null 허용)")
    void findAll_afterV10Migration_returnsSeoyeonVoice() {
        List<Voice> voices = voiceRepository.findAll();

        assertThat(voices).hasSize(1);
        assertThat(voices).extracting(Voice::getId)
                .containsExactly("Seoyeon");
        assertThat(voices).extracting(Voice::getGender)
                .containsExactly("FEMALE");
        // previewUrl은 시드 NULL — non-null을 단언하지 않음
        assertThat(voices).allSatisfy(v -> assertThat(v.getPreviewUrl()).isNull());
    }

    @Test
    @DisplayName("existsById: 시드된 ID → true, 없는 ID → false")
    void existsById_seedId_returnsTrue_unknownId_returnsFalse() {
        assertThat(voiceRepository.existsById("Seoyeon")).isTrue();
        assertThat(voiceRepository.existsById("nonexistent-speaker")).isFalse();
    }
}
