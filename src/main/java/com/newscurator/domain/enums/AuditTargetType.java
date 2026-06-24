package com.newscurator.domain.enums;

/**
 * 어드민 감사(AdminAuditLog) 대상 타입.
 *
 * <p>★ 005 푸시 대상 선택자 {@code AdminTargetType}(ALL/ACCOUNT_IDS/TOPIC_SUBSCRIBERS)과는 별개다 —
 * 그것은 "푸시 수신 대상"을 고르는 enum이고, 이것은 "감사된 변형 행위의 대상 종류"를 분류한다.
 */
public enum AuditTargetType {
    ACCOUNT,
    ARTICLE,
    SCHEDULER,
    NOTICE,
    PUSH,
    EXCLUDED_KEYWORD,
    SUMMARY
}
