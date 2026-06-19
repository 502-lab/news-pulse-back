package com.newscurator.exception;

public class FcmUnregisteredException extends RuntimeException {

    private final String token;

    public FcmUnregisteredException(String token) {
        super("FCM token unregistered: token removed");
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}
