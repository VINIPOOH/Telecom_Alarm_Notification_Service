package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

public record HistoryItemResponse(
        String eventId,
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        AlarmStatus status,
        long timestamp,
        String description) {
}
