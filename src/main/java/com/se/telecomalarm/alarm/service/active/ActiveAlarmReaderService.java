package com.se.telecomalarm.alarm.service.active;

import java.util.List;
import java.util.Map;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.service.AlarmEvent;

public interface ActiveAlarmReaderService {

    List<AlarmEvent> findAllActiveAlarms();

    List<AlarmEvent> findActiveAlarmsByElement(String elementId);

    Map<AlarmSeverity, Long> countAlarmsBySeverity();
}
