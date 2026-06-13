package com.newscurator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.Account;
import com.newscurator.domain.DailyBrief;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.testutil.BigmPostgresImage;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
class DailyBriefRepositoryTest {

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

    @Autowired DailyBriefRepository dailyBriefRepository;
    @Autowired AccountRepository accountRepository;

    private Account testAccount;
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 13);

    @BeforeEach
    void setUp() {
        testAccount = accountRepository.save(Account.builder()
                .email("brieftest@example.com")
                .passwordHash("hash")
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.EMAIL)
                .emailVerified(true)
                .build());
    }

    private DailyBrief buildBrief(Account account, LocalDate date) {
        return DailyBrief.builder()
                .account(account)
                .briefDate(date)
                .articleIds(new Long[]{1L, 2L, 3L})
                .voiceId("harin")
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // (1) UNIQUE(account_id, brief_date) 위반
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 동일 (account_id, brief_date) 두 번 save → DataIntegrityViolationException")
    void save_duplicateAccountIdAndBriefDate_throwsDataIntegrityViolationException() {
        dailyBriefRepository.saveAndFlush(buildBrief(testAccount, TEST_DATE));

        assertThatThrownBy(() ->
                dailyBriefRepository.saveAndFlush(buildBrief(testAccount, TEST_DATE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────────────────────
    // (2) findByAccountIdAndBriefDate: 올바른 엔티티 반환
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) findByAccountIdAndBriefDate: 해당 날짜 브리핑 반환, 다른 날짜 미반환")
    void findByAccountIdAndBriefDate_correctDateReturned() {
        DailyBrief saved = dailyBriefRepository.saveAndFlush(buildBrief(testAccount, TEST_DATE));

        // 다른 날짜 브리핑 저장
        dailyBriefRepository.saveAndFlush(buildBrief(testAccount, TEST_DATE.plusDays(1)));

        Optional<DailyBrief> found =
                dailyBriefRepository.findByAccountIdAndBriefDate(testAccount.getId(), TEST_DATE);
        Optional<DailyBrief> notFound =
                dailyBriefRepository.findByAccountIdAndBriefDate(testAccount.getId(), TEST_DATE.minusDays(1));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getBriefDate()).isEqualTo(TEST_DATE);
        assertThat(found.get().getVoiceId()).isEqualTo("harin");

        assertThat(notFound).isEmpty();
    }
}
