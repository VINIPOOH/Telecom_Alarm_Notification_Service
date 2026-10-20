package com.se.telecomalarm.alarm.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param count how many locks the elements are spread over, at least 1. Two elements that share a lock wait for each
 *              other, so more locks mean less waiting.
 */
@Validated
@ConfigurationProperties(prefix = "alarm.element-locks")
public record ElementLockProperties(
        @Min(1) int count) {
}
