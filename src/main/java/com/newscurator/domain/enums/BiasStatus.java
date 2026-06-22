package com.newscurator.domain.enums;

/**
 * 편향 분석 작업 상태.
 *
 * <p>기존 {@code ProcessingStatus}(PENDING/COMPLETED/FAILED)와 별도 — PROCESSING(claimer 점유 중)과
 * DONE을 명확히 구분해야 SKIP LOCKED claim·lease 회수가 성립한다(research R-001).
 */
public enum BiasStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
