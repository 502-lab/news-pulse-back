package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuditLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLog;

    @BeforeEach
    void setUp() {
        auditLog = (Logger) LoggerFactory.getLogger("AUDIT");
        appender = new ListAppender<>();
        appender.start();
        auditLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLog.detachAppender(appender);
    }

    @Test
    @DisplayName("LOGIN_FAILED 이벤트 — accountId만 포함, 이메일·비밀번호·토큰·코드 미포함")
    void loginFailed_logsAccountIdOnly_noSensitiveData() {
        UUID accountId = UUID.randomUUID();
        AuditLogger.loginFailed(accountId);

        List<ILoggingEvent> logs = appender.list;
        assertThat(logs).hasSize(1);
        String msg = logs.get(0).getFormattedMessage();
        assertThat(msg).contains("LOGIN_FAILED");
        assertThat(msg).contains(accountId.toString());
        // No sensitive data
        assertThat(msg).doesNotContain("@");    // no email
        assertThat(msg).doesNotContain("password");
        assertThat(msg).doesNotContain("token");
        assertThat(msg).doesNotContain("code");
    }

    @Test
    @DisplayName("ACCOUNT_LOCKED 이벤트 — accountId 포함, 민감 정보 미포함")
    void accountLocked_logsAccountId_noSensitiveData() {
        UUID accountId = UUID.randomUUID();
        AuditLogger.accountLocked(accountId);

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("ACCOUNT_LOCKED");
        assertThat(msg).contains(accountId.toString());
        assertThat(msg).doesNotContain("password").doesNotContain("token").doesNotContain("code");
    }

    @Test
    @DisplayName("TOKEN_REUSE_DETECTED 이벤트 — accountId 포함, 토큰 원문 미포함")
    void tokenReuseDetected_logsAccountId_noTokenValue() {
        UUID accountId = UUID.randomUUID();
        AuditLogger.tokenReuseDetected(accountId);

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("TOKEN_REUSE_DETECTED");
        assertThat(msg).contains(accountId.toString());
        // Token UUID in message is accountId, not an actual token string
        assertThat(msg).doesNotContain("Bearer ");
    }

    @Test
    @DisplayName("PASSWORD_CHANGED 이벤트 — accountId 포함, 비밀번호 해시 미포함")
    void passwordChanged_logsAccountId_noPasswordHash() {
        UUID accountId = UUID.randomUUID();
        AuditLogger.passwordChanged(accountId);

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("PASSWORD_CHANGED");
        assertThat(msg).contains(accountId.toString());
        assertThat(msg).doesNotContain("$2a$").doesNotContain("$2b$");  // no bcrypt hash
    }

    @Test
    @DisplayName("ADMIN_ACCESS 이벤트 — accountId + endpoint 포함")
    void adminAccess_logsAccountIdAndEndpoint() {
        UUID accountId = UUID.randomUUID();
        AuditLogger.adminAccess(accountId, "/api/v1/admin/terms");

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("ADMIN_ACCESS");
        assertThat(msg).contains(accountId.toString());
        assertThat(msg).contains("/api/v1/admin/terms");
    }
}
