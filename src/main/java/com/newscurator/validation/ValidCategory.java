package com.newscurator.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = CategoryValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCategory {
    String message() default "유효하지 않은 카테고리 값입니다. 허용: ECONOMY_FINANCE, SCIENCE, POLITICS, SPORTS, WORLD, ENTERTAINMENT_CULTURE, HEALTH_MEDICINE, AUTOMOTIVE, IT, OTHER";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
