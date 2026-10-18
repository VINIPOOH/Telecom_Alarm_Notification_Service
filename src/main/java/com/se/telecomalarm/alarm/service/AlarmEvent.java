package com.se.telecomalarm.alarm.service;

import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;

/**
 * A validated alarm event, as the service sees it.
 */
public record AlarmEvent(
        String eventId,
        String elementId,
        String alarmCode,
        AlarmSeverity severity,
        AlarmStatus status,
        long timestamp,
        String description) {
}
