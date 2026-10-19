package com.se.telecomalarm.alarm.service.notification;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.se.telecomalarm.alarm.config.NotificationProperties;
import com.se.telecomalarm.alarm.domain.NotificationOutcome;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * The notification queue: one {@link ElementQueue} per element on top of one shared set of sender threads, like
 * {@code SerialExecutor} in the javadoc of {@link java.util.concurrent.Executor}.
 * <ul>
 *   <li>{@link #elementIdToEventQueue}: elementId -> {@link ElementQueue}, created once per element by
 *       {@code computeIfAbsent}.</li>
 *   <li>{@link #senders}: {@code pool-size} threads; each {@code drain(elementQueue)} call runs on one of them until
 *       its element queue is empty.</li>
 *   <li>{@link #freeNotificationSlots}: {@code queue-capacity} permits, how many more notifications may be taken in
 *       (waiting in all element queues plus being sent). Taken in {@link #enqueue}, given back after the send and the
 *       {@code notification_sent} update.</li>
 * </ul>
 * <b>Order inside one element:</b> an element queue has at most one {@code drain} at a time, and it takes the next
 * notification only after the previous send has ended. Notifications enter the element queue in commit order because
 * the caller enqueues under the per-element JVM lock.
 * <p>
 * <b>Element queues are never removed</b>: no race "the queue was removed just as a notification arrived"; the cost is
 * memory that grows with the number of distinct elementIds.
 */
@Service
class PerElementNotificationQueueService implements NotificationQueue, SmartLifecycle {

    /**
     * The stop phase: after the web server, so no request can queue a notification any more; before the database
     * connections are closed (that happens only after all lifecycle beans have stopped), so the senders can still
     * write {@code notification_sent} while they finish the waiting notifications.
     */
    private static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;

    private static final Logger log = LoggerFactory.getLogger(PerElementNotificationQueueService.class);
    private final ConcurrentMap<String, ElementQueue> elementIdToEventQueue = new ConcurrentHashMap<>();
    private final Semaphore freeNotificationSlots;
    private final int maxPendingNotifications;
    private final ExecutorService senders;
    private final Duration shutdownTimeout;
    private final NotificationSenderService notificationSenderService;

    PerElementNotificationQueueService(NotificationProperties properties, NotificationSenderService notificationSenderService) {
        this.shutdownTimeout = properties.shutdownTimeout();
        this.maxPendingNotifications = properties.queueCapacity();
        this.freeNotificationSlots = new Semaphore(maxPendingNotifications);
        this.notificationSenderService = notificationSenderService;
        //constraint on max queue of tasks is done by freeNotificationSlots Semaphore
        this.senders = Executors.newFixedThreadPool(properties.poolSize(),
                                                    Thread.ofPlatform().name("notification-sender-", 1).factory());
    }

    @Override
    public NotificationOutcome enqueue(AlarmEvent event) {
        AlarmEventNotification notification = toNotification(event);
        if (!freeNotificationSlots.tryAcquire()) {
            log.warn("Notification queue is full ({} slots): the notification of eventId {} (elementId {}) is dropped",
                     maxPendingNotifications, notification.eventId(), notification.elementId());
            return NotificationOutcome.DROPPED_QUEUE_FULL;
        }
        ElementQueue elementQueue = elementIdToEventQueue.computeIfAbsent(notification.elementId(),
                                                                          elementId -> new ElementQueue());
        // Under the same lock as drain's "empty -> hasDrain = false", or a notification could be left with no drain
        // (see drain). senders.execute() is called under the lock too, as SerialExecutor does: it does not wait for
        // drain(elementQueue), it only hands it over.
        synchronized (elementQueue) {
            if (!elementQueue.hasDrain) {
                try {
                    // Executors default AbortPolicy guarantees correctness independent of actions order under lock, since it will be done in other thread
                    senders.execute(() -> drain(elementQueue));
                    elementQueue.hasDrain = true;
                } catch (RejectedExecutionException sendersStopped) {
                    // senders is shut down: drop the notification. Nothing to undo: nothing has been changed yet.
                    freeNotificationSlots.release();
                    log.warn("Notification senders are stopped: the notification of event {} (element {}) is "
                                     + "dropped", notification.eventId(), notification.elementId());
                    return NotificationOutcome.DROPPED_QUEUE_FULL;
                }
            }
            // In both cases: with a drain that was already there, or with the one just handed over.
            elementQueue.notifications.addLast(notification);
        }
        return NotificationOutcome.QUEUED;
    }

    /**
     * Sends the element queue's notifications one by one until it is empty. The thread is not given back earlier, so
     * a noisy element can hold one sender thread for a long time.
     */
    private void drain(ElementQueue elementQueue) {
        // Only shutdownNow() interrupts these threads (see stop): the rest of the queue is then left unsent.
        while (!Thread.currentThread().isInterrupted()) {
            AlarmEventNotification notification;
            synchronized (elementQueue) {
                notification = elementQueue.notifications.pollFirst();
                if (notification == null) {
                    // "Empty" and "hasDrain = false" in ONE block: otherwise an enqueue in between would see
                    // hasDrain == true, hand over no drain, and its notification would stay with nobody to send it.
                    elementQueue.hasDrain = false;
                    return;
                }
            }
            try {
                // Outside the lock: enqueue never waits for the HTTP call.
                notificationSenderService.deliver(notification);
            } catch (Exception e) {
                // Caught, or drain would end with hasDrain still true and the element stuck. No stack trace: with
                // the receiver down this line comes for every notification.
                log.warn("The notification of event {} (element {}) failed, notification_sent stays false: {}",
                         notification.eventId(), notification.elementId(), e.toString());
            } finally {
                // After the send AND the notification_sent update: pendingCount() counts both.
                freeNotificationSlots.release();
            }
        }
    }

    /**
     * The notifications of one element and whether a {@code drain} for them is handed over (waiting for a thread or
     * running). Both fields only under {@code synchronized (elementQueue)}. Invariant: {@code hasDrain == false}
     * implies no notifications.
     */
    private static final class ElementQueue {
        final Deque<AlarmEventNotification> notifications = new ArrayDeque<>();
        boolean hasDrain;
    }

    /**
     * The body of the notification of an accepted event: its fields as received.
     */
    private static AlarmEventNotification toNotification(AlarmEvent event) {
        return new AlarmEventNotification(event.eventId(), event.elementId(), event.alarmCode(), event.severity(),
                                          event.status(), event.timestamp(), event.description());
    }

    @Override
    public int pendingCount() {
        return maxPendingNotifications - freeNotificationSlots.availablePermits();
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * Graceful stop with a deadline. {@code shutdown()}: new drains are refused (see enqueue), the waiting and running
     * ones go on and empty their element queues. {@code awaitTermination}: waits for them at most
     * {@code shutdown-timeout}. {@code shutdownNow()}: interrupts what is left; those notifications are lost and their
     * slots are never given back, so {@link #pendingCount()} after the stop is the number lost, for the log.
     */
    @Override
    public void stop() {
        senders.shutdown();
        int pending = pendingCount();
        if (pending > 0) {
            log.info("Stopping the notification senders: waiting up to {} for {} notification(s)", shutdownTimeout,
                     pending);
        }
        try {
            if (senders.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the stop itself was interrupted: no more waiting, stop now
        }
        senders.shutdownNow();
        log.warn("The notification senders did not finish within {}: interrupted; {} notification(s) not sent, "
                         + "their notification_sent stays false", shutdownTimeout, pendingCount());
    }

    /**
     * Nothing to start: senders is created in the constructor and works from then on. Spring does not even call this,
     * as {@link #isRunning()} is already true.
     */
    @Override
    public void start() {
    }

    @Override
    public boolean isRunning() {
        return !senders.isShutdown(); // Spring calls stop() only while this is true: once, at the close
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Not stopped when the context is merely paused: once senders is shut down it cannot be started again, so a pause
     * followed by a restart would leave the application with no threads to send notifications.
     */
    @Override
    public boolean isPauseable() {
        return false;
    }
}
