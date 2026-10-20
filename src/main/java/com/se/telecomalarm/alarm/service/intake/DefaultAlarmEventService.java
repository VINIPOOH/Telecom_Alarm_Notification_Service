package com.se.telecomalarm.alarm.service.intake;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.notification.NotificationQueue;
import org.springframework.stereotype.Service;

@Service
class DefaultAlarmEventService implements AlarmEventService {

    private final ElementLocksService elementLocksService;
    private final AlarmEventRecorderService alarmEventRecorderService;
    private final NotificationQueue notificationQueue;

    DefaultAlarmEventService(ElementLocksService elementLocksService, AlarmEventRecorderService alarmEventRecorderService,
                             NotificationQueue notificationQueue) {
        this.elementLocksService = elementLocksService;
        this.alarmEventRecorderService = alarmEventRecorderService;
        this.notificationQueue = notificationQueue;
    }

    /**
     * Order: per-element-id lock, then the transaction ({@link AlarmEventRecorderService#record}), then the
     * notifications, then unlock. Not @Transactional: the transaction is inside that method and has committed when it
     * returns.
     * <ul>
     *   <li>The lock is taken before the transaction, so requests of one element wait without holding a database
     *       connection.</li>
     *   <li>Notifications are queued after the commit, so a rolled-back event is never notified.</li>
     *   <li>The lock is released only after queueing, so for one element the queue order is the commit order.</li>
     * </ul>
     */
    @Override
    public AlarmEventIntakeResult accept(AlarmEvent event) {
        return elementLocksService.callLocked(event.elementId(), () -> recordAndNotify(event));
    }

    private AlarmEventIntakeResult recordAndNotify(AlarmEvent event) {
        AlarmEventRecordResult recordResult = alarmEventRecorderService.record(event); // committed when it returns
        return switch (recordResult.eventOutcome()) {
            case EXACT_REPEAT -> new AlarmEventIntakeResult(false, NotificationOutcome.NOT_SENT_DUPLICATE);
            case REJECTED -> throw new UnknownAlarmException(event.eventId()); // The REJECTED row is committed; now the exception is thrown.
            case ACCEPTED -> {
                // This event first, then the CLEARED events it resurrected. The answer reports only this event's
                // notification; what happened to the others is in the log and in notification_sent.
                NotificationOutcome notificationOutcome = notificationQueue.enqueue(event);
                recordResult.resurrectedClearedEvents().forEach(notificationQueue::enqueue);
                yield new AlarmEventIntakeResult(true, notificationOutcome);
            }
        };
    }
}
