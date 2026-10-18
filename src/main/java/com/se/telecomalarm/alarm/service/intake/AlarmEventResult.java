package com.se.telecomalarm.alarm.service.intake;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;

/**
 * The result of {@link AlarmEventService#accept}.
 *
 * @param created      true: the event was stored for the first time; false: an exact repeat of an accepted event
 * @param notification result of try to send notification
 */
public record AlarmEventResult(
        boolean created,
        NotificationOutcome notification) {
}
