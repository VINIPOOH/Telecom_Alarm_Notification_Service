package com.se.telecomalarm.alarm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.se.telecomalarm.alarm.api.validation.ValidEventTimestamp;
import com.se.telecomalarm.alarm.domain.AlarmEventConstrains;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

/**
 * Body of POST /api/alarms. Everything is mandatory except {@code description}.
 * <p>
 * {@code timestamp} is {@code Long}, not {@code long}: Jackson 3 fails on a missing primitive while parsing,
 * and a missing timestamp must reach Bean Validation as null (reported by {@code @ValidEventTimestamp}).
 * Strings are not trimmed; {@code @NotBlank} rejects only empty and all-whitespace values.
 * Unknown JSON fields are ignored (spring.jackson.deserialization.fail-on-unknown-properties=false).
 */
public record AlarmEventRequest(
        @NotBlank @Size(max = AlarmEventConstrains.ID_MAX_LENGTH)
        String eventId,
        @NotBlank @Size(max = AlarmEventConstrains.ID_MAX_LENGTH)
        String elementId,
        @NotBlank @Size(max = AlarmEventConstrains.ID_MAX_LENGTH)
        String alarmCode,
        @NotNull
        AlarmSeverity severity,
        @NotNull
        AlarmStatus status,
        @ValidEventTimestamp
        Long timestamp,
        @Size(max = AlarmEventConstrains.DESCRIPTION_MAX_LENGTH)
        String description) {
}
