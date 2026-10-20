package com.se.telecomalarm.alarm.api.validation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import com.se.telecomalarm.alarm.config.AlarmIngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The G tests: the "not too far in the future" rule, no Spring, with a fixed clock.
 */
class EventTimestampValidatorTests {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    /**
     * A fixed "now": the validator reads it from the clock it is given.
     */
    private static final long NOW = 1_791_800_000_000L;

    private static final EventTimestampValidator VALIDATOR =
            new EventTimestampValidator(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                                        new AlarmIngestionProperties(TOLERANCE));

    static Stream<Arguments> toleranceBoundary() {
        long toleranceMillis = TOLERANCE.toMillis();
        return Stream.of(
                arguments("now + 5 min", NOW + toleranceMillis, false),
                arguments("now + 5 min + 1 ms", NOW + toleranceMillis + 1, true),
                arguments("now", NOW, false));
    }

    @ParameterizedTest(name = "timestamp = {0}: isLaterThanNowPlusTolerance = {2}")
    @MethodSource("toleranceBoundary")
    @DisplayName("G-04 exact boundary of the future tolerance")
    void g04_toleranceBoundary(String label, long timestamp, boolean expectedTooFar) {
        assertThat(VALIDATOR.isLaterThanNowPlusTolerance(timestamp)).isEqualTo(expectedTooFar);
    }
}
