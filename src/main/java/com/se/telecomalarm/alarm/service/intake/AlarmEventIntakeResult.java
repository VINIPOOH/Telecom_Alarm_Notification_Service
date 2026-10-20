package com.se.telecomalarm.alarm.service.intake;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;

public record AlarmEventIntakeResult(
        boolean isNewEvent,
        NotificationOutcome notificationOutcome) {
}
