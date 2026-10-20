package com.se.telecomalarm.alarm.service.notification;

import com.se.telecomalarm.alarm.domain.NotificationOutcome;
import com.se.telecomalarm.alarm.service.AlarmEvent;

public interface NotificationQueue {

    /**
     * @return what happened to the notification: {@code QUEUED}, or {@code DROPPED_QUEUE_FULL} when the buffer is
     * full or the application is already stopping
     */
    NotificationOutcome enqueue(AlarmEvent event);

    int usedQueueCapacity();
}
