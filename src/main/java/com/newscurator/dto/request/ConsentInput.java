package com.newscurator.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConsentInput(
    @NotNull UUID termsVersionId,
    @NotNull Boolean agreed
) {}
