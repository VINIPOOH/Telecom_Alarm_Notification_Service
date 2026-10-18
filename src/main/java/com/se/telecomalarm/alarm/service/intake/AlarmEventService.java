package com.se.telecomalarm.alarm.service.intake;

import com.se.telecomalarm.alarm.service.AlarmEvent;

public interface AlarmEventService {

    /**
     * @return {@code created = true} for a new event, {@code created = false} for an exact repeat of
     * an accepted one
     * @throws EventIdConflictException the eventId is taken and the request is not an exact repeat
     * @throws UnknownAlarmException    a CLEARED without an earlier accepted RAISED of its alarm; it IS stored, as
     *                                  REJECTED
     */
    AlarmEventResult accept(AlarmEvent event);
}
