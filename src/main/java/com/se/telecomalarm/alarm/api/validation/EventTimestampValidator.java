package com.se.telecomalarm.alarm.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.Duration;
import com.se.telecomalarm.alarm.config.AlarmIngestionProperties;

class EventTimestampValidator implements ConstraintValidator<ValidEventTimestamp, Long> {

    private final Clock clock;

    private final Duration maxFutureOffset;

    public EventTimestampValidator(Clock clock, AlarmIngestionProperties properties) {
        Duration futureTolerance = properties.timestampFutureTolerance();
        if (futureTolerance.isNegative()) {
            throw new IllegalArgumentException("futureTolerance must not be negative: " + futureTolerance);
        }
        this.clock = clock;
        this.maxFutureOffset = futureTolerance;
    }

    private static boolean violation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }

    @Override
    public boolean isValid(Long timestamp, ConstraintValidatorContext context) {
        if (timestamp == null) {
            return violation(context, "must not be null");
        }
        if (timestamp < 0) {
            return violation(context, "must not be negative");
        }
        if (isLaterThanNowPlusTolerance(timestamp)) {
            return violation(context, "must not be more than " + maxFutureOffset.toMillis() + " ms in the future");
        }
        return true;
    }

    //Package-private for the boundary test.
    boolean isLaterThanNowPlusTolerance(long timestamp) {
        long now = clock.millis();
        long toleranceMillis = maxFutureOffset.toMillis();
        // now + toleranceMillis, capped at Long.MAX_VALUE instead of overflowing
        long latestAllowed = now > Long.MAX_VALUE - toleranceMillis ? Long.MAX_VALUE : now + toleranceMillis;
        return timestamp > latestAllowed;
    }
}
