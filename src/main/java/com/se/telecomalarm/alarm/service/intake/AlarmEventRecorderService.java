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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database part of accepting an event: steps 1-5 of {@link #record} in one transaction.
 * Isolation must stay READ COMMITTED. Under REPEATABLE READ the snapshot is taken by the first statement, which is
 * the lock itself, before the wait, so we would not see what the transaction we waited for committed.
 */
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
     * Decides whether the event is accepted and writes it.
     *
     * @throws EventIdConflictException the eventId is taken, and this is not an exact repeat of an accepted event
     */
    @Transactional
    public Recorded record(AlarmEvent event) {
        String elementId = event.elementId();
        String alarmCode = event.alarmCode();

        //Lock the alarm first, so nothing we read can change before we write.
        alarmPairLockRepository.lock(elementId, alarmCode);
        AlarmEventAcceptance acceptance = decide(event);
        if (!insert(event, acceptance)) {
            return duplicateOrConflict(event);
        }

        if (acceptance == AlarmEventAcceptance.REJECTED) {
            return Recorded.rejected();
        }

        //A new RAISED accepts the rejected CLEARED of this alarm that come after it.
        List<AlarmEvent> resurrected = event.status() == AlarmStatus.RAISED
                                       ? resurrectClearedAfter(event)
                                       : List.of();

        //Update active_alarms to match the history.
        syncActive(elementId, alarmCode);

        return Recorded.accepted(resurrected);
    }

    //ACCEPTED for a RAISED, or for a CLEARED that has an accepted RAISED of the alarm before it; REJECTED otherwise.
    private AlarmEventAcceptance decide(AlarmEvent event) {
        if (event.status() == AlarmStatus.RAISED
                || alarmEventRepository.hasAcceptedRaisedAlarmBefore(event.elementId(), event.alarmCode(), event.timestamp())) {
            return AlarmEventAcceptance.ACCEPTED;
        }
        return AlarmEventAcceptance.REJECTED;
    }

    /**
     * Turns the rejected CLEARED of the alarm that are later than {@code raised} into ACCEPTED
     */
    private List<AlarmEvent> resurrectClearedAfter(AlarmEvent raised) {
        List<AlarmEventEntity> rejected = alarmEventRepository.findRejectedClearedAlarmsAfterGivenAlarm(
                raised.elementId(), raised.alarmCode(), raised.timestamp());
        rejected.forEach(row -> row.setAcceptance(AlarmEventAcceptance.ACCEPTED));
        return rejected.stream()
                .map(alarmEventEntityToDataMapper::toEvent)
                .sorted(alarmEventComparator)
                .toList();
    }

    /**
     * Points active_alarms at the latest accepted event of the alarm if it is a RAISED, or removes the row if it
     * is a CLEARED. The old and the new row have different ids, so it does not matter that Hibernate writes the INSERT before the DELETE.
     */
    private void syncActive(String elementId, String alarmCode) {
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

    /**
     * Returns true if the event was inserted, false if an event with this eventId exists.
     */
    private boolean insert(AlarmEvent event, AlarmEventAcceptance acceptance) {
        int insertedRows = alarmEventRepository.insertRowIfAbsent(event.eventId(), event.elementId(), event.alarmCode(),
                                                                  event.severity().name(), event.status().name(),
                                                                  event.timestamp(), event.description(), acceptance.name());
        return insertedRows == 1;
    }

    /**
     * The eventId already exists. The same data as an ACCEPTED event is a repeat; anything else is a conflict
     *
     * @throws EventIdConflictException not an exact repeat of an accepted event
     */
    private Recorded duplicateOrConflict(AlarmEvent event) {
        AlarmEventEntity stored = alarmEventRepository.findById(event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "INSERT found event_id " + event.eventId() + " taken, but no stored row was read"));
        if (stored.getAcceptance() == AlarmEventAcceptance.REJECTED
                || !alarmEventEquivalenceService.isSameEvent(stored, event)) {
            throw new EventIdConflictException(event.eventId());
        }
        return Recorded.exactRepeat();
    }

    /**
     * The latest accepted event of the alarm. The query returns the events with the latest timestamp; the comparator
     * picks one if there are several.
     */
    private Optional<AlarmEvent> findLatestAcceptedAlarm(String elementId, String alarmCode) {
        return alarmEventRepository.findAcceptedByAlarmAtLatestTimestamp(elementId, alarmCode).stream()
                .map(alarmEventEntityToDataMapper::toEvent)
                .max(alarmEventComparator);
    }
}
