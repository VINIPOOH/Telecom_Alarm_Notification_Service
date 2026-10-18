package com.se.telecomalarm.alarm.service;

import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import org.springframework.stereotype.Component;

/**
 * Turns a stored row of {@code alarm_events} into the service type {@link AlarmEvent}. The entity holds the data only; the
 * conversion is here, in the service layer, so the db layer does not know the service types.
 * <p>
 * A bean, injected where it is used, not a static utility: the same rule as for the other logic of the service
 * (data in records and entities, behaviour in beans), so that a conversion that one day needs a dependency does not
 * change its callers. Stateless; a test creates it with {@code new}.
 */
@Component
public class AlarmEventEntityToDataMapper {

    public AlarmEvent toEvent(AlarmEventEntity row) {
        return new AlarmEvent(row.getEventId(), row.getElementId(), row.getAlarmCode(), row.getSeverity(), row.getStatus(),
                              row.getEventTimestamp(), row.getDescription());
    }
}
