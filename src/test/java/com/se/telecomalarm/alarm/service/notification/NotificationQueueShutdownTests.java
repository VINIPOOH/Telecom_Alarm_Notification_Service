package com.se.telecomalarm.alarm.service.notification;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.se.telecomalarm.alarm.config.NotificationProperties;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import com.se.telecomalarm.alarm.domain.NotificationOutcome;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E-14: the stop of the notification queue (README, decision 9.8), tested on the queue itself, without Spring and
 * without a database. {@link PerElementNotificationQueueService#stop()} is the method Spring calls when the
 * context closes.
 * <p>
 * The sender is replaced by a fake: either it "sends" at once, or it hangs until its thread is interrupted, like a send
 * to a receiver that does not answer. The hanging fake reports the interrupt wrapped in another exception, so the
 * interrupt flag of the thread is cleared, the worst case for the sending loop.
 */
class NotificationQueueShutdownTests {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMillis(300);
    private static final Duration MARGIN = Duration.ofSeconds(2);

    private PerElementNotificationQueueService queue;

    private static AlarmEvent event(String eventId, String elementId) {
        return new AlarmEvent(eventId, elementId, "CELL_DOWN", AlarmSeverity.MAJOR, AlarmStatus.RAISED, 1_000L, null);
    }

    private static PerElementNotificationQueueService newQueue(NotificationSenderService sender) {
        return new PerElementNotificationQueueService(new NotificationProperties(2, 10, SHUTDOWN_TIMEOUT), sender);
    }

    private static Duration timeOf(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @AfterEach
    void stopQueue() {
        if (queue != null && queue.isRunning()) {
            queue.stop();
        }
    }

    @Test
    @DisplayName("E-14 a stop with a working receiver sends every queued notification and waits less than the deadline")
    void stopWithWorkingReceiver_sendsEverything() {
        RecordingSender sender = new RecordingSender();
        queue = newQueue(sender);
        for (int i = 1; i <= 5; i++) {
            assertThat(queue.enqueue(event("e" + i, i % 2 == 0 ? "BTS-1" : "BTS-2"))).isEqualTo(NotificationOutcome.QUEUED);
        }

        Duration took = timeOf(queue::stop);

        assertThat(sender.sent()).containsExactlyInAnyOrder("e1", "e2", "e3", "e4", "e5");
        assertThat(queue.usedQueueCapacity()).as("notifications left after the stop").isZero();
        assertThat(took).as("time of the stop").isLessThan(SHUTDOWN_TIMEOUT);
        assertThat(queue.isRunning()).as("isRunning after the stop").isFalse();
    }

    @Test
    @DisplayName("E-14 a stop with a hung receiver ends at the deadline, and the interrupted sender takes no next notification")
    void stopWithHungReceiver_endsAtDeadline_andSendsNothingMore() throws InterruptedException {
        HangingSender sender = new HangingSender();
        queue = newQueue(sender);
        for (int i = 1; i <= 3; i++) {
            assertThat(queue.enqueue(event("e" + i, "BTS-1"))).isEqualTo(NotificationOutcome.QUEUED);
        }
        assertThat(sender.started.await(5, TimeUnit.SECONDS)).as("the first send has started").isTrue();

        Duration took = timeOf(queue::stop);

        assertThat(took).as("time of the stop")
                .isGreaterThanOrEqualTo(SHUTDOWN_TIMEOUT)
                .isLessThan(SHUTDOWN_TIMEOUT.plus(MARGIN));
        // The interrupted send gives its place back; the two notifications behind it are never sent and keep theirs.
        await().atMost(MARGIN).until(() -> queue.usedQueueCapacity() == 2);
        // Without the interrupt flag restored, the sender would take e2 here and hang again.
        await().during(Duration.ofMillis(300)).atMost(MARGIN).until(() -> sender.calls.get() == 1);
    }

    @Test
    @DisplayName("E-14 after the stop, enqueue answers DROPPED_QUEUE_FULL and keeps no place in the buffer")
    void enqueueAfterStop_isDropped() {
        RecordingSender sender = new RecordingSender();
        queue = newQueue(sender);
        queue.stop();

        assertThat(queue.enqueue(event("e1", "BTS-1"))).isEqualTo(NotificationOutcome.DROPPED_QUEUE_FULL);
        assertThat(queue.usedQueueCapacity()).isZero();
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    @DisplayName("E-14 after a stop at the deadline, enqueue for the interrupted element is dropped, not reported QUEUED")
    void enqueueAfterInterruptedStop_forSameElement_isDropped() throws InterruptedException {
        HangingSender sender = new HangingSender();
        queue = newQueue(sender);
        queue.enqueue(event("e1", "BTS-1"));
        assertThat(sender.started.await(5, TimeUnit.SECONDS)).as("the first send has started").isTrue();
        queue.stop();
        await().atMost(MARGIN).until(() -> queue.usedQueueCapacity() == 0);

        assertThat(queue.enqueue(event("e2", "BTS-1"))).isEqualTo(NotificationOutcome.DROPPED_QUEUE_FULL);
        assertThat(queue.usedQueueCapacity()).isZero();
    }

    /**
     * Records the eventIds it was given and returns at once, as after a 2xx.
     */
    private static final class RecordingSender extends NotificationSenderService {

        private final List<String> sent = new CopyOnWriteArrayList<>();

        RecordingSender() {
            super(null, null);
        }

        @Override
        void deliver(AlarmEventNotification notification) {
            sent.add(notification.eventId());
        }

        List<String> sent() {
            return sent;
        }
    }

    /**
     * Hangs until its thread is interrupted, then throws the interrupt wrapped in another exception (as an HTTP client
     * may), which leaves the interrupt flag cleared.
     */
    private static final class HangingSender extends NotificationSenderService {

        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        HangingSender() {
            super(null, null);
        }

        @Override
        void deliver(AlarmEventNotification notification) {
            calls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new IllegalStateException("send interrupted", e);
            }
        }
    }
}
