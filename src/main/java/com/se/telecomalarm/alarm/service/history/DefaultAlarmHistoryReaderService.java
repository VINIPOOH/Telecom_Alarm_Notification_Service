package com.se.telecomalarm.alarm.service.history;

import java.util.List;
import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import com.se.telecomalarm.alarm.db.repository.AlarmEventRepository;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.AlarmEventEntityToDataMapper;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
class DefaultAlarmHistoryReaderService implements AlarmHistoryReaderService {

    private final AlarmEventRepository alarmEventRepository;
    private final AlarmEventEntityToDataMapper alarmEventEntityToDataMapper;

    DefaultAlarmHistoryReaderService(AlarmEventRepository alarmEventRepository,
                                     AlarmEventEntityToDataMapper alarmEventEntityToDataMapper) {
        this.alarmEventRepository = alarmEventRepository;
        this.alarmEventEntityToDataMapper = alarmEventEntityToDataMapper;
    }

    /**
     * Asks for one row more than the page: if it comes, there is a next page.
     */
    @Override
    public HistoryPage readPage(String elementId, String alarmCode, HistoryPosition after, int limit) {
        Limit limitPlusOne = Limit.of(limit + 1);
        List<AlarmEventEntity> rows = after == null
                                      ? alarmEventRepository.findAcceptedFirstPage(elementId, alarmCode, limitPlusOne)
                                      : alarmEventRepository.findAcceptedNextPage(elementId, alarmCode, after.timestamp(), after.eventId(), limitPlusOne);

        List<AlarmEvent> items = rows.stream()
                .limit(limit)
                .map(alarmEventEntityToDataMapper::toEvent)
                .toList();
        return new HistoryPage(items, rows.size() > limit);
    }
}
