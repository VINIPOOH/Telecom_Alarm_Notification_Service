package com.se.telecomalarm.alarm.api.dto;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;

/**
 * Body of the 201 / 200 answer: the id of the event and what this request did with the notification.
 */
public record AlarmEventResponse(
        String eventId,
        NotificationOutcome notification) {
}
