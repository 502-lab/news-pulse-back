package com.newscurator.dto.response;

public record TokenPairResponse(
    String accessToken,
    String refreshToken,
    long expiresIn
) {}
