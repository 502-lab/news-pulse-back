package com.newscurator.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Structured security event audit logger (FR-028).
 * Logs accountId UUID only — never logs email, password, token, or verification code.
 */
public final class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private AuditLogger() {}

    public static void loginFailed(UUID accountId) {
        withContext("LOGIN_FAILED", accountId,
                () -> log.warn("[AUDIT] event=LOGIN_FAILED accountId={}", accountId));
    }

    public static void accountLocked(UUID accountId) {
        withContext("ACCOUNT_LOCKED", accountId,
                () -> log.warn("[AUDIT] event=ACCOUNT_LOCKED accountId={}", accountId));
    }

    public static void tokenReuseDetected(UUID accountId) {
        withContext("TOKEN_REUSE_DETECTED", accountId,
                () -> log.warn("[AUDIT] event=TOKEN_REUSE_DETECTED accountId={}", accountId));
    }

    public static void passwordChanged(UUID accountId) {
        withContext("PASSWORD_CHANGED", accountId,
                () -> log.info("[AUDIT] event=PASSWORD_CHANGED accountId={}", accountId));
    }

    public static void adminAccess(UUID accountId, String endpoint) {
        withContext("ADMIN_ACCESS", accountId,
                () -> log.info("[AUDIT] event=ADMIN_ACCESS accountId={} endpoint={}", accountId, endpoint));
    }

    private static void withContext(String event, UUID accountId, Runnable action) {
        MDC.put("auditEvent", event);
        if (accountId != null) {
            MDC.put("accountId", accountId.toString());
        }
        try {
            action.run();
        } finally {
            MDC.remove("auditEvent");
            MDC.remove("accountId");
        }
    }
}
