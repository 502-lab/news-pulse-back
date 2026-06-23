package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.service.admin.AdminAuditService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 ① AdminAuditService TX 참여(실 PG, 008 plan ③).
 *
 * <p>{@code record()}가 호출자의 트랜잭션에 <b>참여(REQUIRED join, REQUIRES_NEW 아님)</b>함을 증명한다:
 * 외부 TX 롤백 시 감사 행도 함께 롤백(고아 감사 0), 커밋 시 1건 존재. 005 outbox claimer의
 * REQUIRES_NEW 격리(외부 롤백에도 별도 TX는 살아남음)의 정반대 방향이다.
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
class AdminAuditTxParticipationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_audit_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminAuditService auditService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE admin_audit_log RESTART IDENTITY");
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, role, signup_type) VALUES (?, ?, 'ADMIN', 'EMAIL')",
                actorId, "admin-" + actorId + "@test.com");
    }

    private long auditRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_log", Long.class);
    }

    @Test
    @DisplayName("외부 TX 커밋 시 audit 1건 존재")
    void record_outerCommit_keepsAudit() {
        new TransactionTemplate(txManager)
                .executeWithoutResult(
                        status ->
                                auditService.record(
                                        actorId,
                                        "TEST_ACTION",
                                        AuditTargetType.ACCOUNT,
                                        "target-1",
                                        Map.of("before", "USER", "after", "ADMIN")));

        assertThat(auditRows()).as("외부 TX 커밋 → 감사 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 외부 TX 롤백 시 audit 행도 롤백(고아 감사 0) = 같은 TX 참여 증명")
    void record_outerRollback_rollsBackAudit() {
        new TransactionTemplate(txManager)
                .executeWithoutResult(
                        status -> {
                            auditService.record(
                                    actorId,
                                    "TEST_ACTION",
                                    AuditTargetType.ACCOUNT,
                                    "target-1",
                                    Map.of("k", "v"));
                            // 외부 변형작업이 실패했다고 가정 → 강제 롤백
                            status.setRollbackOnly();
                        });

        // record()가 REQUIRES_NEW였다면 별도 TX 커밋으로 1건 남았을 것 → 0이어야 같은 TX 참여 입증
        assertThat(auditRows())
                .as("외부 롤백 → 감사도 롤백(REQUIRED join, 고아 감사 0)")
                .isZero();
    }
}
