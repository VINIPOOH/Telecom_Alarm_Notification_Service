package com.se.telecomalarm.alarm.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the per-element JVM lock ({@code alarm.element-locks.*}), see {@link com.se.telecomalarm.alarm.service.ElementLocks}.
 * The value is set in {@code application.yaml} only, there is no default in the code: a missing key stops the start.
 * <p>
 * <b>Checked at start</b> by Bean Validation ({@code @Validated}): a wrong or missing value stops the start with the
 * list of all violations, instead of an application that runs and silently misbehaves.
 *
 * @param stripes how many locks the elements are spread over ({@code alarm.element-locks.stripes}). Two elements on
 *                one stripe wait for each other without need: more stripes make that rarer, at the cost of one lock
 *                object per stripe.
 */
@Validated
@ConfigurationProperties(prefix = "alarm.element-locks")
public record ElementLockProperties(
        @Min(1) int stripes) {
}
