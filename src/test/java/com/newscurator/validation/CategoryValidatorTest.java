package com.newscurator.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryValidatorTest {

    static Validator validator;

    record Wrapper(@ValidCategory List<String> categories) {}

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("001 정본 Category 값만 포함된 목록 → 위반 없음")
    void valid_categories_passValidation() {
        var wrapper = new Wrapper(List.of("IT", "ECONOMY_FINANCE", "POLITICS", "SPORTS",
                "WORLD", "ENTERTAINMENT_CULTURE", "HEALTH_MEDICINE", "AUTOMOTIVE",
                "SCIENCE", "OTHER"));
        Set<ConstraintViolation<Wrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("비정본 문자열(TECH) 포함 → 위반 발생")
    void invalid_category_TECH_failsValidation() {
        var wrapper = new Wrapper(List.of("IT", "TECH", "SCIENCE"));
        Set<ConstraintViolation<Wrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).contains("TECH");
    }

    @Test
    @DisplayName("비정본 문자열(ECONOMY) 포함 → 위반 발생")
    void invalid_category_ECONOMY_failsValidation() {
        var wrapper = new Wrapper(List.of("ECONOMY", "POLITICS", "SPORTS"));
        Set<ConstraintViolation<Wrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).contains("ECONOMY");
    }

    @Test
    @DisplayName("null 목록 → null 허용(null 처리는 @NotNull 담당)")
    void null_list_passesValidator() {
        var wrapper = new Wrapper(null);
        Set<ConstraintViolation<Wrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("빈 목록 → 위반 없음(크기 검증은 @Size 담당)")
    void empty_list_passesValidator() {
        var wrapper = new Wrapper(List.of());
        Set<ConstraintViolation<Wrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isEmpty();
    }
}
