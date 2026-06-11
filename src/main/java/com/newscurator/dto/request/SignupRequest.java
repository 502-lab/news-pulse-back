package com.newscurator.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record SignupRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotNull @Valid List<ConsentInput> consents,
    Boolean ageConfirmed
) {}
