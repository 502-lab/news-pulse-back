package com.newscurator.exception;

/**
 * 관리자가 자기 자신의 역할 강등·비활성화를 시도할 때(008 FR-014 a). 자기-lockout 방지.
 */
public class SelfMutationForbiddenException extends RuntimeException {
    public SelfMutationForbiddenException(String message) {
        super(message);
    }
}
