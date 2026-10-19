package com.se.telecomalarm.alarm.service.notification;

import jakarta.annotation.Nullable;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

/**
 * The body of one notification POST:
 * The JSON form is the same as an item of GET /api/alarms/history ({@code HistoryItemResponse})
 * {@code timestamp} is epoch milliseconds as received.
 */
public record AlarmEventNotification(
        String eventId,
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        AlarmStatus status,
        long timestamp,
        @Nullable
        String description) {
}
