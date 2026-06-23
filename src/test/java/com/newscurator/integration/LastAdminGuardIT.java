package com.newscurator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.request.LoginRequest;
import com.newscurator.exception.AccountSuspendedException;
import com.newscurator.exception.LastAdminProtectedException;
import com.newscurator.exception.SelfMutationForbiddenException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.service.AuthService;
import com.newscurator.service.admin.AdminUserService;
import com.newscurator.testutil.BigmPostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 크라운주얼 ① 자기보호 가드(008 FR-014 a/b, 실 PG) — discriminating.
 *
 * <p>(a) 자기 자신 강등/비활성 금지(self), (b) 마지막 ADMIN 강등/비활성 금지(last-admin),
 * 단 ADMIN 2명이면 1명 강등은 허용(가드가 "마지막"만 막음), (c) 비활성 계정 로그인 차단(002 경로).
 * + role 변경 시 audit 1건(before/after).
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
class LastAdminGuardIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(BigmPostgresImage.NAME)
                    .withDatabaseName("newscurator_guard_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private AdminUserService adminUserService;
    @Autowired private AuthService authService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE admin_audit_log, accounts RESTART IDENTITY CASCADE");
    }

    private Account account(String email, AccountRole role, String rawPassword) {
        return accountRepository.save(
                Account.builder()
                        .email(email)
                        .passwordHash(rawPassword == null ? null : passwordEncoder.encode(rawPassword))
                        .role(role)
                        .status(AccountStatus.ACTIVE)
                        .emailVerified(true)
                        .onboardingCompleted(true)
                        .signupType(SignupType.EMAIL)
                        .build());
    }

    private AccountRole roleOf(UUID id) {
        return accountRepository.findById(id).orElseThrow().getRole();
    }

    @Test
    @DisplayName("(a) 자기 자신 role 강등 금지 — ADMIN 2명이라 last-admin 아님에도 self로 거부, role 불변")
    void selfDemotion_forbidden() {
        Account a = account("admin-a@test.com", AccountRole.ADMIN, "pw");
        account("admin-b@test.com", AccountRole.ADMIN, "pw"); // ADMIN 2명 → last-admin 가드 비대상

        assertThatThrownBy(
                        () -> adminUserService.changeRole(a.getId(), a.getId(), AccountRole.USER))
                .isInstanceOf(SelfMutationForbiddenException.class);
        assertThat(roleOf(a.getId())).as("거부 → role 불변(ADMIN)").isEqualTo(AccountRole.ADMIN);
    }

    @Test
    @DisplayName("(b) 마지막 ADMIN 강등 금지 — actor≠target(self 아님)인데도 last-admin으로 거부, 불변")
    void lastAdminDemotion_forbidden() {
        Account onlyAdmin = account("only-admin@test.com", AccountRole.ADMIN, "pw");
        // actor는 self가 아닌 임의 id(서비스 가드는 actor의 ADMIN 여부가 아니라 대상 마지막ADMIN을 본다)
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                adminUserService.changeRole(
                                        actor, onlyAdmin.getId(), AccountRole.USER))
                .isInstanceOf(LastAdminProtectedException.class);
        assertThat(roleOf(onlyAdmin.getId())).isEqualTo(AccountRole.ADMIN);
    }

    @Test
    @DisplayName("(b') discriminating — ADMIN 2명이면 1명 강등 허용(가드는 '마지막'만 막음)")
    void demotion_allowed_whenNotLastAdmin() {
        Account a = account("admin-a@test.com", AccountRole.ADMIN, "pw");
        Account b = account("admin-b@test.com", AccountRole.ADMIN, "pw");

        // a가 b를 강등(self 아님, ADMIN 2명이라 last 아님) → 허용
        adminUserService.changeRole(a.getId(), b.getId(), AccountRole.USER);

        assertThat(roleOf(b.getId())).as("일반 강등은 통과").isEqualTo(AccountRole.USER);
        assertThat(accountRepository.countByRole(AccountRole.ADMIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("(c) 비활성화 후 로그인 차단 — SUSPENDED 계정 로그인 시 거부(002 경로)")
    void deactivatedAccount_loginBlocked() {
        Account admin = account("admin@test.com", AccountRole.ADMIN, "pw");
        Account user = account("user@test.com", AccountRole.USER, "secret123");

        // admin이 user 비활성화
        adminUserService.changeStatus(admin.getId(), user.getId(), false);
        assertThat(accountRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.SUSPENDED);

        // 비활성 계정 로그인 → 차단
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@test.com", "secret123")))
                .isInstanceOf(AccountSuspendedException.class);
    }

    @Test
    @DisplayName("② role 변경 USER→ADMIN audit 1건 + detail before=USER/after=ADMIN")
    void roleChange_writesAuditWithDiff() {
        Account admin = account("admin@test.com", AccountRole.ADMIN, "pw");
        Account user = account("user@test.com", AccountRole.USER, "pw");

        adminUserService.changeRole(admin.getId(), user.getId(), AccountRole.ADMIN);

        assertThat(roleOf(user.getId())).isEqualTo(AccountRole.ADMIN);

        Long auditRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_log WHERE action='ROLE_CHANGE'"
                                + " AND target_id = ? AND detail->>'before'='USER' AND detail->>'after'='ADMIN'",
                        Long.class,
                        user.getId().toString());
        assertThat(auditRows).as("audit 1건 + diff(before=USER, after=ADMIN)").isEqualTo(1L);
    }
}
