package com.se.telecomalarm.alarm.service.intake;

import java.util.List;
import java.util.Optional;
import com.se.telecomalarm.alarm.db.entity.ActiveAlarmEntity;
import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import com.se.telecomalarm.alarm.db.repository.ActiveAlarmRepository;
import com.se.telecomalarm.alarm.db.repository.AlarmEventRepository;
import com.se.telecomalarm.alarm.db.repository.AlarmPairLockRepository;
import com.se.telecomalarm.alarm.domain.AlarmEventAcceptance;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.AlarmEventEntityToDataMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlarmEventRecorderService {

    private final AlarmPairLockRepository alarmPairLockRepository;
    private final AlarmEventRepository alarmEventRepository;
    private final ActiveAlarmRepository activeAlarmRepository;
    private final AlarmEventComparator alarmEventComparator;
    private final AlarmEventEquivalenceService alarmEventEquivalenceService;
    private final AlarmEventEntityToDataMapper alarmEventEntityToDataMapper;

    AlarmEventRecorderService(AlarmPairLockRepository alarmPairLockRepository, AlarmEventRepository alarmEventRepository,
                              ActiveAlarmRepository activeAlarmRepository,
                              AlarmEventComparator alarmEventComparator, AlarmEventEquivalenceService alarmEventEquivalenceService,
                              AlarmEventEntityToDataMapper alarmEventEntityToDataMapper) {
        this.alarmPairLockRepository = alarmPairLockRepository;
        this.alarmEventRepository = alarmEventRepository;
        this.activeAlarmRepository = activeAlarmRepository;
        this.alarmEventComparator = alarmEventComparator;
        this.alarmEventEquivalenceService = alarmEventEquivalenceService;
        this.alarmEventEntityToDataMapper = alarmEventEntityToDataMapper;
    }

    /**
     * Isolation must stay READ COMMITTED. Under REPEATABLE READ the snapshot is taken by the first statement, which is
     * the lock itself, before the wait, so we would not see what the transaction we waited for committed.
     *
     * @throws EventIdConflictException the eventId is taken, and this is not an exact repeat of an accepted event
     */
    @Transactional
    public AlarmEventRecordResult record(AlarmEvent event) {
        String elementId = event.elementId();
        String alarmCode = event.alarmCode();

        //Lock the alarm first, so nothing we read can change before we write.
        alarmPairLockRepository.lock(elementId, alarmCode);
        AlarmEventAcceptance acceptance = decideAcceptance(event);
        if (!tryInsert(event, acceptance)) {
            return decideDuplicateOrConflicted(event);
        }
        return acceptance == AlarmEventAcceptance.ACCEPTED
               ? resurrectAndSyncActiveAlarms(event)
               : AlarmEventRecordResult.rejected();
    }

    private AlarmEventRecordResult resurrectAndSyncActiveAlarms(AlarmEvent event) {
        List<AlarmEvent> resurrected = getResurrectedAlarms(event);
        synchronizeActiveAlarms(event.elementId(), event.alarmCode());
        return AlarmEventRecordResult.accepted(resurrected);
    }

    private @NonNull List<AlarmEvent> getResurrectedAlarms(AlarmEvent event) {
        return event.status() == AlarmStatus.RAISED
               ? resurrectClearedAlarmsAfter(event)
               : List.of();
    }

    private AlarmEventAcceptance decideAcceptance(AlarmEvent event) {
        if (event.status() == AlarmStatus.RAISED
                || alarmEventRepository.hasAcceptedRaisedAlarmBefore(event.elementId(), event.alarmCode(), event.timestamp())) {
            return AlarmEventAcceptance.ACCEPTED;
        }
        return AlarmEventAcceptance.REJECTED;
    }

    private List<AlarmEvent> resurrectClearedAlarmsAfter(AlarmEvent raised) {
        List<AlarmEventEntity> resurrected = alarmEventRepository.acceptRejectedClearedAlarmsAfter(
                raised.elementId(), raised.alarmCode(), raised.timestamp());
        return resurrected.stream()
                .map(alarmEventEntityToDataMapper::toEvent)
                .sorted(alarmEventComparator)
                .toList();
    }

    private void synchronizeActiveAlarms(String elementId, String alarmCode) {
        AlarmEvent latest = findLatestAcceptedAlarm(elementId, alarmCode)
                .orElseThrow(() -> new IllegalStateException(
                        "no accepted event of the alarm (" + elementId + ", " + alarmCode + ") after accepting one"));
        Optional<ActiveAlarmEntity> active =
                activeAlarmRepository.findByHistory_ElementIdAndHistory_AlarmCode(elementId, alarmCode);

        if (latest.status() == AlarmStatus.CLEARED) {
            active.ifPresent(activeAlarmRepository::delete);
            return;
        }
        if (active.isPresent() && active.get().getEventId().equals(latest.eventId())) {
            return;
        }
        active.ifPresent(activeAlarmRepository::delete);

        activeAlarmRepository.save(new ActiveAlarmEntity(
                alarmEventRepository.getReferenceById(latest.eventId())));// Only the id is needed, so the event is not loaded.
    }

    private boolean tryInsert(AlarmEvent event, AlarmEventAcceptance acceptance) {
        int insertedRows = alarmEventRepository.insertRowIfAbsent(event.eventId(), event.elementId(), event.alarmCode(),
                                                                  event.severity().name(), event.status().name(),
                                                                  event.timestamp(), event.description(), acceptance.name());
        return insertedRows == 1;
    }

    private AlarmEventRecordResult decideDuplicateOrConflicted(AlarmEvent event) {
        AlarmEventEntity stored = alarmEventRepository.findById(event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "INSERT found event_id " + event.eventId() + " taken, but no stored row was read"));
        if (stored.getAcceptance() == AlarmEventAcceptance.REJECTED
                || !alarmEventEquivalenceService.isSameEvent(stored, event)) {
            throw new EventIdConflictException(event.eventId());
        }
        return AlarmEventRecordResult.exactRepeat();
    }

    //The query returns the events with the latest timestamp; the comparator picks one if there are several.
    private Optional<AlarmEvent> findLatestAcceptedAlarm(String elementId, String alarmCode) {
        return alarmEventRepository.findAcceptedByAlarmAtLatestTimestamp(elementId, alarmCode).stream()
                .map(alarmEventEntityToDataMapper::toEvent)
                .max(alarmEventComparator);
    }
}
