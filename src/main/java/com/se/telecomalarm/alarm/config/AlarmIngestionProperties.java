package com.se.telecomalarm.alarm.config;

import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the intake of events ({@code alarm.ingestion.*}). The value is set in {@code application.yaml} only,
 * there is no default in the code: a missing key stops the start.
 * <p>
 * <b>Checked at start</b> by Bean Validation ({@code @Validated}): a wrong or missing value stops the start with the
 * list of all violations, instead of an application that runs and silently misbehaves.
 *
 * @param timestampFutureTolerance how far ahead of the server's "now" an event timestamp may be
 *                                 ({@code alarm.ingestion.timestamp-future-tolerance})
 */
@Validated
@ConfigurationProperties(prefix = "alarm.ingestion")
public record AlarmIngestionProperties(
        @NotNull @DurationMin(millis = 0) Duration timestampFutureTolerance) {
}
