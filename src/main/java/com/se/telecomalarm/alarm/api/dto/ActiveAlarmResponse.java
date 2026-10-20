package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;

public record ActiveAlarmResponse(
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        String description,
        String eventId,
        long timestamp) {
}
