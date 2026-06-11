package com.newscurator.exception;

public class OAuthStateInvalidException extends RuntimeException {
    public OAuthStateInvalidException(String message) {
        super(message);
    }
}
