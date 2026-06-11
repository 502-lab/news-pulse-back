package com.newscurator.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AccountSummaryResponse(
    UUID id,
    String email,
    String role,
    boolean emailVerified,
    boolean onboardingCompleted,
    String signupType,
    Instant createdAt,
    boolean requiresReConsent
) {}
