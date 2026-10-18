package com.se.telecomalarm.alarm.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The event time (epoch milliseconds) must be present, must not be negative and must not be later than
 * "now + tolerance". This constraint checks null itself, so the field needs no separate {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = EventTimestampValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEventTimestamp {

    String message() default "is not a valid event timestamp";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
