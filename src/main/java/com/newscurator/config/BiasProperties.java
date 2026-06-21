package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 편향 분석 파이프라인 설정.
 *
 * <p>two-tx claimer + lease 모델(research R-013): claim 시 next_retry_at을 NOW()+leaseMinutes로
 * 미뤄 크래시 stuck 행을 lease 만료 후 회수한다. 재시도 백오프는 005 outbox 컨벤션 재사용
 * (총 3회 시도 후 FAILED, 그 뒤 6h one-shot 복구).
 */
@ConfigurationProperties(prefix = "app.scheduler.bias")
public record BiasProperties(
        long intervalMs,
        int batchSize,
        long recoveryIntervalMs,
        int backoffAttempt1Minutes,
        int backoffAttempt2Minutes,
        int leaseMinutes) {

    public BiasProperties {
        if (batchSize <= 0) {
            batchSize = 10;
        }
        if (leaseMinutes <= 0) {
            leaseMinutes = 5;
        }
        if (backoffAttempt1Minutes <= 0) {
            backoffAttempt1Minutes = 5;
        }
        if (backoffAttempt2Minutes <= 0) {
            backoffAttempt2Minutes = 30;
        }
    }
}
