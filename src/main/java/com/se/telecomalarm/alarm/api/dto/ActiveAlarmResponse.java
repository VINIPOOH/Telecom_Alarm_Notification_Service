package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;

/**
 * One record of GET /api/alarms/active: the last accepted event of an active pair (SPEC section 4).
 * <p>
 * Exactly these six fields, there is no {@code status} (every record in the list is RAISED). {@code timestamp} is
 * epoch milliseconds as received. A null {@code description} is written as {@code "description": null}, the key is
 * not left out: Jackson's default inclusion is kept, do not add {@code @JsonInclude(NON_NULL)} here.
 */
public record ActiveAlarmResponse(
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        String description,
        String eventId,
        long timestamp) {
}
