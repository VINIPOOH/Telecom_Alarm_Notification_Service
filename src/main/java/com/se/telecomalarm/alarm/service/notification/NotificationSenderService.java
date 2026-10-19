package com.se.telecomalarm.alarm.service.notification;

import com.se.telecomalarm.alarm.db.repository.AlarmEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Delivery of ONE notification, in a sender thread of {@link PerElementNotificationQueueService}: the POST to the receiver,
 * then {@code notification_sent = true}.
 * <p>
 * Success is a 2xx answer and nothing else. Everything else is an exception for the caller, which
 * logs it and goes on with the next notification.
 * <p>
 * <b>The connection to the database</b> is taken only for the UPDATE, after the HTTP call has ended, and given back
 * at its commit ({@link AlarmEventRepository#markNotificationSent}): a sender waiting for a slow receiver holds a
 * thread, never a connection of the DB pool.
 */
@Service
class NotificationSenderService {

    private final NotificationClient notificationClient;
    private final AlarmEventRepository alarmEventRepository;

    NotificationSenderService(NotificationClient notificationClient, AlarmEventRepository alarmEventRepository) {
        this.notificationClient = notificationClient;
        this.alarmEventRepository = alarmEventRepository;
    }

    /**
     * Sends the notification and records the delivery; throws if it was not delivered with a 2xx.
     */
    void deliver(AlarmEventNotification notification) {
        ResponseEntity<Void> answer = notificationClient.send(notification);
        if (!answer.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("the receiver answered " + answer.getStatusCode() + ", not 2xx");
        }
        alarmEventRepository.markNotificationSent(notification.eventId());
    }
}
