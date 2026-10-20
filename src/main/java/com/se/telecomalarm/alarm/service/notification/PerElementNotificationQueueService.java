package com.se.telecomalarm.alarm.service.notification;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 *   <li>{@link #elementQueues}: one {@link ElementQueue} per elementId, created once by
 *       {@code computeIfAbsent}.</li>
 *   <li>{@link #senders}: {@code pool-size} threads; each {@code sendUntilEmpty(elementQueue)} call runs on one of them until
 *       its element queue is empty.</li>
 *   <li>{@link #freeNotificationSlots}: {@code queue-capacity} permits, how many more notifications may be taken in
 *       (waiting in all element queues plus being sent). Taken in {@link #enqueue}, given back after the send and the
 *       {@code notification_sent} update.</li>
 * </ul>
 * <b>Order inside one element:</b> an element queue has at most one sender at a time, and it takes the next
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
    private final ConcurrentMap<String, ElementQueue> elementQueues = new ConcurrentHashMap<>();
    private final Semaphore freeNotificationSlots;
    private final int totalQueueCapacity;
    private final ExecutorService senders;
    private final Duration shutdownTimeout;
    private final NotificationSenderService notificationSenderService;

    PerElementNotificationQueueService(NotificationProperties properties, NotificationSenderService notificationSenderService) {
        this.shutdownTimeout = properties.shutdownTimeout();
        this.totalQueueCapacity = properties.queueCapacity();
        this.freeNotificationSlots = new Semaphore(totalQueueCapacity);
        this.notificationSenderService = notificationSenderService;
        //constraint on max queue of tasks is done by freeNotificationSlots Semaphore
        this.senders = Executors.newFixedThreadPool(properties.poolSize(),
                                                    Thread.ofPlatform().name("notification-sender-", 1).factory());
    }

    @Override
    public NotificationOutcome enqueue(AlarmEvent event) {
        AlarmEventNotification notification = toNotification(event);
        // Checked first: after an interrupted stop an element queue can keep hasSender == true with no sender running,
        // and a notification added to it would be reported QUEUED but never sent.
        if (senders.isShutdown()) {
            log.warn("Notification senders are stopped: the notification of event {} (element {}) is dropped",
                     notification.eventId(), notification.elementId());
            return NotificationOutcome.DROPPED_QUEUE_FULL;
        }
        if (!freeNotificationSlots.tryAcquire()) {
            log.warn("Notification queue is full ({} slots): the notification of eventId {} (elementId {}) is dropped",
                     totalQueueCapacity, notification.eventId(), notification.elementId());
            return NotificationOutcome.DROPPED_QUEUE_FULL;
        }
        ElementQueue elementQueue = elementQueues.computeIfAbsent(notification.elementId(),
                                                                  elementId -> new ElementQueue());
        // Flag check and add under one lock, the same one the sender takes before it quits. Otherwise the sender could quit
        // between them, and the notification would be left with nobody to send it.
        synchronized (elementQueue) {
            if (!elementQueue.hasSender) {
                try {
                    // sendUntilEmpty(...) always runs in another thread (Executors' default AbortPolicy) and waits for this
                    // lock, so the order of the steps inside this block does not matter.
                    senders.execute(() -> sendUntilEmpty(elementQueue));
                    elementQueue.hasSender = true;
                } catch (RejectedExecutionException sendersStopped) {
                    // senders is shut down: drop the notification. Nothing to undo: nothing has been changed yet.
                    freeNotificationSlots.release();
                    log.warn("Notification senders are stopped: the notification of event {} (element {}) is "
                                     + "dropped", notification.eventId(), notification.elementId());
                    return NotificationOutcome.DROPPED_QUEUE_FULL;
                }
            }
            // In both cases: with a sender that was already there, or with the one just handed over.
            elementQueue.notifications.offer(notification);
        }
        return NotificationOutcome.QUEUED;
    }

    @Override
    public int pendingCount() {
        return totalQueueCapacity - freeNotificationSlots.availablePermits();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    /**
     * Nothing to start: senders is created in the constructor and works from then on. Spring does not even call this,
     * as {@link #isRunning()} is already true.
     */
    @Override
    public void start() {
    }

    /**
     * Graceful stop with a deadline. {@code shutdown()}: new senders are refused (see enqueue), the waiting and running
     * ones go on and empty their element queues. {@code awaitTermination}: waits for them at most
     * {@code shutdown-timeout}. {@code shutdownNow()}: interrupts what is left; those notifications are lost and their
     * slots are never given back, so {@link #pendingCount()} after the stop is the number lost, for the log.
     */
    @Override
    public void stop() {
        senders.shutdown();
        log.info("Stopping the notification senders: waiting up to {} for {} notification(s)", shutdownTimeout,
                 pendingCount());
        try {
            if (senders.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Notification senders stopped: no notifications left in the queue");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the stop itself was interrupted: no more waiting, stop now
        }
        senders.shutdownNow();
        log.warn("The notification senders did not finish within {}: interrupted; {} notification(s) not sent, "
                         + "their notification_sent stays false", shutdownTimeout, pendingCount());
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

    // ---- sending ----------------------------------------------------------------------------------------------------

    private static AlarmEventNotification toNotification(AlarmEvent event) {
        return new AlarmEventNotification(event.eventId(), event.elementId(), event.alarmCode(), event.severity(),
                                          event.status(), event.timestamp(), event.description());
    }

    private void sendUntilEmpty(ElementQueue elementQueue) {
        // Only shutdownNow() interrupts these threads (see stop): the rest of the queue is then left unsent.
        while (!Thread.currentThread().isInterrupted()) {
            AlarmEventNotification notification = elementQueue.notifications.poll();
            if (notification != null) {
                deliverAndFreeSlot(notification); // outside the lock: enqueue never waits for the HTTP call
            } else if (stopIfStillEmpty(elementQueue)) {
                return;
            }
        }
    }

    /**
     * Sends one notification and gives its slot back. Never throws: a failure is logged, so sendUntilEmpty goes on with the
     * next notification instead of ending with hasSender still true and the element stuck.
     */
    private void deliverAndFreeSlot(AlarmEventNotification notification) {
        try {
            notificationSenderService.deliver(notification);
        } catch (Exception e) {
            // An interrupt (shutdownNow in stop) may arrive wrapped in another exception, and catching an
            // InterruptedException clears the thread's interrupt flag. Without restoring it, the sender would not see the
            // stop and would go on with the next notification.
            if (causedByInterrupt(e)) {
                Thread.currentThread().interrupt();
            }
            // No stack trace: with the receiver down this line comes for every notification.
            log.warn("The notification of event {} (element {}) failed, notification_sent stays false: {}",
                     notification.eventId(), notification.elementId(), e.toString());
        } finally {
            // After the send AND the notification_sent update: pendingCount() counts both.
            freeNotificationSlots.release();
        }
    }

    private static boolean causedByInterrupt(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checked again under the lock that enqueue also takes: either we see a notification it added after our poll(),
     * or it sees hasSender == false after us and starts a new sender. Nothing is left without a sender.
     */
    private static boolean stopIfStillEmpty(ElementQueue elementQueue) {
        synchronized (elementQueue) {
            if (elementQueue.notifications.isEmpty()) {
                elementQueue.hasSender = false;
                return true;
            }
            return false;
        }
    }

    /**
     * The notifications of one element and whether a sender for them is handed over (waiting for a thread or
     * running). {@code notifications} is a concurrent queue: the sender takes from it without a lock, while every add
     * (enqueue) and every {@code hasSender} change happen under {@code synchronized (elementQueue)}. Invariant, at
     * every moment the lock is free: {@code hasSender == false} implies no notifications.
     */
    private static final class ElementQueue {
        final Queue<AlarmEventNotification> notifications = new ConcurrentLinkedQueue<>();
        boolean hasSender;
    }
}
