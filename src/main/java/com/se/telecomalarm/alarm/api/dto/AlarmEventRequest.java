package com.se.telecomalarm.alarm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.se.telecomalarm.alarm.api.validation.ValidEventTimestamp;
import com.se.telecomalarm.alarm.domain.AlarmEventConstrains;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

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
