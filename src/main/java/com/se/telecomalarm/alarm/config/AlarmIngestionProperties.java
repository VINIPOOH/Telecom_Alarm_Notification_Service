package com.se.telecomalarm.alarm.config;

import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param timestampFutureTolerance how far ahead of the server's "now" an event timestamp may be ({@code alarm.ingestion.timestamp-future-tolerance})
 */
@Validated
@ConfigurationProperties(prefix = "alarm.ingestion")
public record AlarmIngestionProperties(
        @NotNull @DurationMin(millis = 0) Duration timestampFutureTolerance) {
}
