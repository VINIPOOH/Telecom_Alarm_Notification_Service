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
     * Accepting an event: per-element JVM lock -> transaction ({@link AlarmEventRecorderService#record}) -> commit ->
     * notifications -> unlock. Not {@code @Transactional}: the commit has happened when {@code record} returns.
     * <ul>
     *   <li>the JVM lock is taken before the transaction, so requests of one element wait without holding a pool
     *       connection;</li>
     *   <li>notifications are queued after the commit: a rolled-back event never gets queue;</li>
     *   <li>the lock is released after the notifications are queued, so for one element the queue order is the commit
     *       order.</li>
     * </ul>
     */
    @Override
    public AlarmEventResult accept(AlarmEvent event) {
        return elementLocksService.callLocked(event.elementId(), () -> recordAndNotify(event));
    }

    private AlarmEventResult recordAndNotify(AlarmEvent event) {
        Recorded recorded = alarmEventRecorderService.record(event); // committed when it returns
        return switch (recorded.outcome()) {
            case EXACT_REPEAT -> new AlarmEventResult(false, NotificationOutcome.NOT_SENT_DUPLICATE);
            case REJECTED -> throw new UnknownAlarmException(event.eventId()); // The REJECTED row is committed; now the exception is thrown.
            case ACCEPTED -> {
                // The RAISED first, then the CLEARED it resurrected. The result reports only the
                // notification only for processed event; what happened to the others is in the logs and notification_sent db column
                NotificationOutcome notification = notificationQueue.enqueue(event);
                recorded.resurrected().forEach(notificationQueue::enqueue);
                yield new AlarmEventResult(true, notification);
            }
        };
    }
}
