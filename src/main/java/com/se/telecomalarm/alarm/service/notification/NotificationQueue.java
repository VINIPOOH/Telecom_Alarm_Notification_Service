package com.se.telecomalarm.alarm.service.notification;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;
import com.se.telecomalarm.alarm.service.AlarmEvent;

public interface NotificationQueue {

    /**
     * @return what happened to the notification: {@code QUEUED}, or {@code DROPPED_QUEUE_FULL} when the buffer is
     * full or the application is already stopping
     */
    NotificationOutcome enqueue(AlarmEvent event);

    /**
     * How many notifications occupy the buffer right now: waiting in the queue plus being sent at this moment.
     * {@code 0} means nothing is being sent and nothing is left to send. Used by the tests and may be used as a metric of the
     * queue depth.
     */
    int pendingCount();
}
