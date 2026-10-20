package com.se.telecomalarm.alarm.service;

import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import org.springframework.stereotype.Component;

@Component
public class AlarmEventEntityToDataMapper {

    public AlarmEvent toEvent(AlarmEventEntity row) {
        return new AlarmEvent(row.getEventId(), row.getElementId(), row.getAlarmCode(), row.getSeverity(), row.getStatus(),
                              row.getEventTimestamp(), row.getDescription());
    }
}
