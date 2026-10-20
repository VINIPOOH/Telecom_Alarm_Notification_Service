package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;

public record AlarmEventResponse(
        String eventId,
        NotificationOutcome notification) {
}
