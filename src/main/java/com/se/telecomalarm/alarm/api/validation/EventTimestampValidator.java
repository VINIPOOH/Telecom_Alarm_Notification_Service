package com.se.telecomalarm.alarm.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.Duration;
import com.se.telecomalarm.alarm.config.AlarmIngestionProperties;

/**
 * Validator of {@link ValidEventTimestamp}: the event time must be present, must not be negative and must not be
 * later than "now + tolerance". Spring creates it through its validator factory and injects the constructor arguments.
 * <p>
 * "Now" is the injected {@link Clock}, never {@code System.currentTimeMillis()}: a test passes
 * {@code Clock.fixed(...)} and checks the exact boundary through {@link #tooFarInFuture(long)}, without Spring and
 * without a {@link ConstraintValidatorContext}.
 */
class EventTimestampValidator implements ConstraintValidator<ValidEventTimestamp, Long> {

    private final Clock clock;

    private final Duration futureTolerance;

    /**
     * @param clock      the source of "now"
     * @param properties the future tolerance ({@code alarm.ingestion.timestamp-future-tolerance}); checked at start
     *                   by Bean Validation, and here again because a test creates this class without Spring
     */
    public EventTimestampValidator(Clock clock, AlarmIngestionProperties properties) {
        Duration futureTolerance = properties.timestampFutureTolerance();
        if (futureTolerance.isNegative()) {
            throw new IllegalArgumentException("futureTolerance must not be negative: " + futureTolerance);
        }
        this.clock = clock;
        this.futureTolerance = futureTolerance;
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
        if (tooFarInFuture(timestamp)) {
            return violation(context, "must not be more than " + futureTolerance.toMillis() + " ms in the future");
        }
        return true;
    }

    /**
     * Whether {@code timestamp} (epoch milliseconds) is later than now plus the tolerance.
     * A timestamp exactly at now + tolerance is still allowed. Package-private for the boundary test.
     */
    boolean tooFarInFuture(long timestamp) {
        long now = clock.millis();
        long toleranceMillis = futureTolerance.toMillis();
        // now + toleranceMillis, capped at Long.MAX_VALUE instead of overflowing
        long latestAllowed = now > Long.MAX_VALUE - toleranceMillis ? Long.MAX_VALUE : now + toleranceMillis;
        return timestamp > latestAllowed;
    }
}
