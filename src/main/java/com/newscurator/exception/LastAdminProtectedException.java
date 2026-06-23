package com.newscurator.exception;

/**
 * 마지막 ADMIN의 강등·비활성화를 시도할 때(008 FR-014 b). 운영자 0명 상태 방지.
 */
public class LastAdminProtectedException extends RuntimeException {
    public LastAdminProtectedException(String message) {
        super(message);
    }
}
