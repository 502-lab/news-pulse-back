package com.newscurator.exception;

/**
 * 어드민 작업 대상(사용자·기사 등)이 존재하지 않을 때(008). 404.
 */
public class AdminTargetNotFoundException extends RuntimeException {
    public AdminTargetNotFoundException(String message) {
        super(message);
    }
}
