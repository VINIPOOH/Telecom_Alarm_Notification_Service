package com.se.telecomalarm.alarm.service.notification;

import com.se.telecomalarm.alarm.db.repository.AlarmEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Sends one notification (in a sender thread of {@link PerElementNotificationQueueService}), then sets
 * notification_sent = true. Anything but a 2xx answer is an exception; the caller logs it and goes on with the next
 * notification.
 * <p>
 * The database connection is taken only for that UPDATE, after the HTTP call has ended: a sender waiting for a slow
 * receiver holds a thread, never a connection of the database pool.
 */
@Service
class NotificationSenderService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSenderService.class);

    private final NotificationClient notificationClient;
    private final AlarmEventRepository alarmEventRepository;

    NotificationSenderService(NotificationClient notificationClient, AlarmEventRepository alarmEventRepository) {
        this.notificationClient = notificationClient;
        this.alarmEventRepository = alarmEventRepository;
    }

    void deliver(AlarmEventNotification notification) {
        ResponseEntity<Void> answer = notificationClient.send(notification);
        if (!answer.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("the receiver answered " + answer.getStatusCode() + ", not 2xx");
        }
        // Not 1 only if the event row is gone: it is committed before the notification is queued and never deleted.
        if (alarmEventRepository.markNotificationSent(notification.eventId()) != 1) {
            log.warn("The notification of event {} (element {}) was sent, but notification_sent was not set: "
                     + "the event row was not found", notification.eventId(), notification.elementId());
        }
    }
}
