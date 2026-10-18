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

/**
 * Read only and without the Alarm lock: every method here is one SELECT, which under READ COMMITTED sees one snapshot
 * of both tables and takes no row locks, so it neither waits for a writer nor makes one wait.
 */
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

    /**
     * The query returns only the groups that have rows (GROUP BY), so the zeros are added here.
     */
    @Override
    public Map<AlarmSeverity, Long> countAlarmsBySeverity() {
        Map<AlarmSeverity, Long> counts = new EnumMap<>(AlarmSeverity.class);
        for (AlarmSeverity severity : AlarmSeverity.values()) {
            counts.put(severity, 0L);
        }
        for (Object[] row : activeAlarms.countActiveBySeverity()) {
            counts.put((AlarmSeverity) row[0], ((Number) row[1]).longValue());
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
