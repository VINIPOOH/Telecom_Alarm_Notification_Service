package com.se.telecomalarm.alarm.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param poolSize        sender threads shared by all elements; one element uses at most one at a time
 * @param queueCapacity   buffer places over all elements; a notification holds its place until it is sent
 * @param shutdownTimeout how long the stop of the application waits for the queued notifications
 */
@Validated
@ConfigurationProperties(prefix = "alarm.notification")
public record NotificationProperties(
        @Min(1) int poolSize,
        @Min(1) int queueCapacity,
        @NotNull @DurationMin(millis = 0) Duration shutdownTimeout) {
}
