package com.newscurator.integration;

import static org.assertj.core.api.Assertions.*;

import com.newscurator.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 5분 blast-window 쿼리(countRecentFamilyBlastsByAccountId)를 실제 DB로 검증.
 *
 * blast 이벤트 신호: blasted_at 컬럼 — reuse-attack 경로(TokenService.rotate()의 grace 초과)에서만 set된다.
 * 로그아웃(revoke), 정상 회전(consume only)은 blasted_at을 set하지 않으므로 카운트에 포함되지 않는다.
 *
 * 쿼리: SELECT COUNT(DISTINCT familyId) ... WHERE blastedAt IS NOT NULL AND blastedAt > :since
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenBlastWindowTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("newscurator_blast_it")
                    .withUsername("test")
                    .withPassword("test");

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final String ADMIN_HASH = encoder.encode("Admin@test123!");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.scheduler.enabled", () -> "false");
        registry.add("app.client.gemini.api-key", () -> "test-key");
        registry.add("app.client.gemini.base-url", () -> "http://localhost:9999");
        registry.add("app.client.naver.client-id", () -> "test-id");
        registry.add("app.client.naver.client-secret", () -> "test-secret");
        registry.add("app.client.naver.base-url", () -> "http://localhost:9999");
        registry.add("email-service.base-url", () -> "http://localhost:9999");
        registry.add("email-service.api-key", () -> "test-api-key");
        registry.add("spring.flyway.placeholders.admin-email", () -> "admin@blast.local");
        registry.add("spring.flyway.placeholders.admin-password-hash", () -> ADMIN_HASH);
    }

    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE refresh_tokens, consent_records, verification_codes, accounts RESTART IDENTITY CASCADE");

        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) "
                        + "VALUES ('blasttest@example.com', 'hash', 'USER', 'ACTIVE', true, 'EMAIL')");
        accountId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'blasttest@example.com'", String.class));
    }

    /**
     * blast 이벤트 시뮬레이션: blasted_at만 set한 토큰을 삽입.
     * blasted_at이 blast의 실제 발생 시각이며, consumedAt과 독립적이다.
     */
    private void insertBlastedToken(UUID familyId, Instant blastedAt) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens "
                        + "(account_id, family_id, token_hash, issued_at, expires_at, is_revoked,"
                        + " consumed_at, blasted_at) "
                        + "VALUES (?::uuid, ?::uuid, ?, NOW() - INTERVAL '1 hour',"
                        + " NOW() + INTERVAL '30 days', true, NOW() - INTERVAL '2 hours', ?)",
                accountId.toString(),
                familyId.toString(),
                "bhash_" + familyId,
                java.sql.Timestamp.from(blastedAt));
    }

    /**
     * 정상 회전 시뮬레이션: consumed_at 설정, blasted_at=NULL, is_revoked=FALSE.
     * blast가 전혀 없는 상태를 나타낸다.
     */
    private void insertNormallyRotatedToken(UUID familyId, Instant consumedAt) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens "
                        + "(account_id, family_id, token_hash, issued_at, expires_at, is_revoked, consumed_at) "
                        + "VALUES (?::uuid, ?::uuid, ?, NOW() - INTERVAL '1 hour',"
                        + " NOW() + INTERVAL '30 days', false, ?)",
                accountId.toString(),
                familyId.toString(),
                "rhash_" + familyId,
                java.sql.Timestamp.from(consumedAt));
    }

    /**
     * 로그아웃 시뮬레이션: is_revoked=TRUE이지만 blasted_at=NULL.
     * 로그아웃은 revokeByFamilyId를 호출하지만 blast 신호를 set하지 않는다.
     */
    private void insertLoggedOutToken(UUID familyId, Instant consumedAt) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens "
                        + "(account_id, family_id, token_hash, issued_at, expires_at, is_revoked, consumed_at) "
                        + "VALUES (?::uuid, ?::uuid, ?, NOW() - INTERVAL '1 hour',"
                        + " NOW() + INTERVAL '30 days', true, ?)",
                accountId.toString(),
                familyId.toString(),
                "lhash_" + familyId,
                java.sql.Timestamp.from(consumedAt));
    }

    @Test
    @DisplayName("5분 내 2개 패밀리 blast → count=2 → 에스컬레이션 임계값(2) 충족")
    void countBlasts_twoBlastedFamiliesWithin5min_returns2() {
        Instant now = Instant.now();
        insertBlastedToken(UUID.randomUUID(), now.minusSeconds(120)); // blast 2분 전
        insertBlastedToken(UUID.randomUUID(), now.minusSeconds(240)); // blast 4분 전

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, now.minusSeconds(300));

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("blast 1개 5분 안, 1개 5분 초과 → count=1 → 에스컬레이션 미달")
    void countBlasts_oneInsideOneOutside5minWindow_returns1() {
        Instant now = Instant.now();
        insertBlastedToken(UUID.randomUUID(), now.minusSeconds(120)); // blast 2분 전 (window 내)
        insertBlastedToken(UUID.randomUUID(), now.minusSeconds(360)); // blast 6분 전 (window 밖)

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, now.minusSeconds(300));

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("[false-escalation 가드] 정상 회전 N회(blast 없음) → count=0 → 에스컬레이션 안 됨")
    void countBlasts_normalRotationsOnly_noBlast_returns0() {
        // 5회 정상 회전: consumed_at 설정, blasted_at=NULL, is_revoked=FALSE
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            insertNormallyRotatedToken(UUID.randomUUID(), now.minusSeconds(i * 30L));
        }

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, now.minusSeconds(300));

        // 정상 회전은 blasted_at이 null이므로 카운트 불가 — false-escalation 방지
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("[false-escalation 가드] 최근 로그아웃(is_revoked=true, blasted_at=null) → count=0")
    void countBlasts_recentLogout_noBlastSignal_returns0() {
        // 로그아웃: revokeByFamilyId 호출 → is_revoked=TRUE. 그러나 blasted_at은 set 안 됨.
        Instant now = Instant.now();
        insertLoggedOutToken(UUID.randomUUID(), now.minusSeconds(60));  // 1분 전 로그아웃
        insertLoggedOutToken(UUID.randomUUID(), now.minusSeconds(120)); // 2분 전 로그아웃

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, now.minusSeconds(300));

        // 로그아웃은 blast가 아님 — blasted_at=null이므로 카운트 불가
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("blast 없이 blasted_at=null인 폐기 토큰 → count=0")
    void countBlasts_revokedWithoutBlastedAt_returns0() {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens "
                        + "(account_id, family_id, token_hash, issued_at, expires_at, is_revoked) "
                        + "VALUES (?::uuid, ?::uuid, 'no_blast_hash',"
                        + " NOW() - INTERVAL '1 hour', NOW() + INTERVAL '30 days', true)",
                accountId.toString(),
                UUID.randomUUID().toString());

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, Instant.now().minusSeconds(300));

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("다른 계정의 blast → 내 count에 미반영")
    void countBlasts_differentAccount_notCounted() {
        jdbcTemplate.update(
                "INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type) "
                        + "VALUES ('other@example.com', 'hash', 'USER', 'ACTIVE', true, 'EMAIL')");
        UUID otherId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'other@example.com'", String.class));

        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens "
                        + "(account_id, family_id, token_hash, issued_at, expires_at, is_revoked, blasted_at) "
                        + "VALUES (?::uuid, ?::uuid, 'other_blast',"
                        + " NOW()-INTERVAL '1h', NOW()+INTERVAL '30d', true, ?)",
                otherId.toString(),
                UUID.randomUUID().toString(),
                java.sql.Timestamp.from(now.minusSeconds(60)));

        long count = refreshTokenRepository.countRecentFamilyBlastsByAccountId(
                accountId, now.minusSeconds(300));

        assertThat(count).isEqualTo(0);
    }
}
