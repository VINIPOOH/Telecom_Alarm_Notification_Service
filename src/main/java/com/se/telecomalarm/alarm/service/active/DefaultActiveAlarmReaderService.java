package com.se.telecomalarm.alarm.service.active;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.se.telecomalarm.alarm.db.entity.ActiveAlarmEntity;
import com.se.telecomalarm.alarm.db.repository.ActiveAlarmRepository;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.AlarmEventEntityToDataMapper;
import org.springframework.stereotype.Service;

@Service
class DefaultActiveAlarmReaderService implements ActiveAlarmReaderService {

    private final ActiveAlarmRepository activeAlarms;
    private final AlarmEventEntityToDataMapper alarmEventEntityToDataMapper;

    DefaultActiveAlarmReaderService(ActiveAlarmRepository activeAlarms, AlarmEventEntityToDataMapper alarmEventEntityToDataMapper) {
        this.activeAlarms = activeAlarms;
        this.alarmEventEntityToDataMapper = alarmEventEntityToDataMapper;
    }

    @Override
    public List<AlarmEvent> findAllActiveAlarms() {
        return toEvents(activeAlarms.findAllByOrderByHistory_ElementIdAscHistory_AlarmCodeAsc());
    }

    @Override
    public List<AlarmEvent> findActiveAlarmsByElement(String elementId) {
        return toEvents(activeAlarms.findAllByHistory_ElementIdOrderByHistory_AlarmCodeAsc(elementId));
    }

    @Override
    public Map<AlarmSeverity, Long> countAlarmsBySeverity() {
        Map<AlarmSeverity, Long> counts = zeroForEverySeverity();
        for (Object[] row : activeAlarms.countActiveBySeverity()) {
            counts.put((AlarmSeverity) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static Map<AlarmSeverity, Long> zeroForEverySeverity() {
        Map<AlarmSeverity, Long> counts = new EnumMap<>(AlarmSeverity.class);
        for (AlarmSeverity severity : AlarmSeverity.values()) {
            counts.put(severity, 0L);
        }
        return counts;
    }

    private List<AlarmEvent> toEvents(List<ActiveAlarmEntity> rows) {
        return rows.stream()
                .map(ActiveAlarmEntity::getHistory)
                .map(alarmEventEntityToDataMapper::toEvent)
                .toList();
    }
}
