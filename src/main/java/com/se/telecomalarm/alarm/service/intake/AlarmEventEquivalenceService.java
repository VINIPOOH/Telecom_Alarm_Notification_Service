package com.se.telecomalarm.alarm.service.intake;

import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.AlarmEventEntityToDataMapper;
import org.springframework.stereotype.Service;

@Service
class AlarmEventEquivalenceService {

    private final AlarmEventEntityToDataMapper alarmEventEntityToDataMapper;

    AlarmEventEquivalenceService(AlarmEventEntityToDataMapper alarmEventEntityToDataMapper) {
        this.alarmEventEntityToDataMapper = alarmEventEntityToDataMapper;
    }

    boolean isSameEvent(AlarmEventEntity stored, AlarmEvent received) {
        return alarmEventEntityToDataMapper.toEvent(stored).equals(received);
    }
}
