package com.newscurator.validation;

import com.newscurator.domain.enums.Category;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CategoryValidator implements ConstraintValidator<ValidCategory, List<String>> {

    private static final Set<String> VALID_VALUES = Arrays.stream(Category.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

    @Override
    public boolean isValid(List<String> categories, ConstraintValidatorContext context) {
        if (categories == null) {
            return true;
        }
        List<String> invalid = categories.stream()
                .filter(c -> c != null && !VALID_VALUES.contains(c))
                .toList();
        if (invalid.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "유효하지 않은 카테고리: " + String.join(", ", invalid)
                        + ". 허용값: " + String.join(", ", VALID_VALUES)
        ).addConstraintViolation();
        return false;
    }
}
