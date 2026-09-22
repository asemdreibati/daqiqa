package io.daqiqa.inventory.adapter.in.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/** The number must not be zero: the contract's {@code not: {const: 0}}, which Bean Validation has no constraint for. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NonZero.Validator.class)
@interface NonZero {

    String message() default "must not be zero";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<NonZero, Integer> {

        @Override
        public boolean isValid(@Nullable Integer value, ConstraintValidatorContext context) {
            return value == null || value != 0; // absence is @NotNull's business
        }
    }
}
