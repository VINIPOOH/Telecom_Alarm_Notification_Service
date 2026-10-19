package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

/**
 * One item of GET /api/alarms/history: the full event as it was received, with {@code status} (API decisions,
 * "Форматы ответов", 16.10.2026).
 * <p>
 * Exactly these seven fields. There is no {@code result} (the history shows only ACCEPTED events, so it would always
 * be the same) and no {@code notification_sent} (an internal detail). {@code timestamp} is epoch milliseconds as
 * received. A null {@code description} is written as {@code "description": null}, the key is not left out: Jackson's
 * default inclusion is kept, do not add {@code @JsonInclude(NON_NULL)} here.
 */
public record HistoryItemResponse(
        String eventId,
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        AlarmStatus status,
        long timestamp,
        String description) {
}
