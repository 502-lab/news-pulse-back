package com.newscurator.exception;

public class TokenReusedException extends RuntimeException {
    public TokenReusedException(String message) {
        super(message);
    }
}
