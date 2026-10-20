package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-07 / E-08 (a full notification queue) and the "notification was dropped" variant of B-11.
 * <p>
 * Own Spring context, by necessity: the queue capacity is read when the application starts, and these tests need a
 * capacity of {@value #CAPACITY}. A different property makes a different context key, so Spring starts a second
 * application for this class, with its own WireMock server and its own PostgreSQL container; it is cached and reused
 * by all tests of this class.
 * <p>
 * Why exactly {@value #CAPACITY} events get {@code QUEUED}: a place in the buffer is taken when a notification is
 * queued and given back only after its send attempt has finished (executor notes, section 4.3). With a hung receiver
 * the first notification is on the wire and the second waits behind it, so both places stay taken while the test
 * sends its events; that takes far less than the read timeout, after which the first send would give its place back.
 */
@TestPropertySource(properties = "alarm.notification.queue-capacity=" + NotificationQueueFullTests.CAPACITY)
class NotificationQueueFullTests extends AlarmStateTestBase {

    static final int CAPACITY = 2;

    /**
     * How many events more than the capacity are sent.
     */
    private static final int OVERFLOW = 2;

    private static void assertDuplicateAnswer(ApiResponse response, String eventId) {
        assertStatus(response, 200);
        assertThat(response.jsonObject()).as("body of %s", response)
                .isEqualTo(Map.of("eventId", eventId, "notification", "NOT_SENT_DUPLICATE"));
    }

    @Test
    @DisplayName("E-07 a full queue: every event is accepted (201), the first ones are QUEUED, the extra ones DROPPED_QUEUE_FULL with notification_sent = false")
    void e07_fullQueue_acceptsEveryEvent_dropsTheExtraNotifications() {
        List<TestEvent> events = overflowTheQueue("E07");

        assertThat(storedResults()).as("every event is stored as accepted")
                .containsOnlyKeys(events.stream().map(TestEvent::eventId).toList())
                .allSatisfy((eventId, result) -> assertThat(result).isEqualTo(ACCEPTED));
        receiverAnswers(ok()); // let the queued ones finish, so the flags are final
        List<String> dropped = events.subList(CAPACITY, events.size()).stream().map(TestEvent::eventId).toList();
        assertNotificationNotSent(dropped.toArray(String[]::new));
        assertThat(receivedEventIds()).as("only the queued notifications ever reached the receiver")
                .containsExactly(events.subList(0, CAPACITY).stream().map(TestEvent::eventId).toArray(String[]::new));
    }

    @Test
    @DisplayName("E-08 once the receiver is released and the queue has emptied, a new event is QUEUED and delivered")
    void e08_afterTheQueueEmptied_newEventIsQueuedAndDelivered() {
        overflowTheQueue("E08");
        receiverAnswers(ok());
        awaitNotificationQueueEmpty();

        ApiResponse response = post(raised(at(10), "MAJOR", "E08-next"));

        assertStatus(response, 201);
        assertThat(response.jsonObject()).as("body of %s", response)
                .isEqualTo(Map.of("eventId", "E08-next", "notification", "QUEUED"));
        awaitNotificationSent("E08-next");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("B-11 a repeat of an event whose notification was dropped (queue full) still answers 200 NOT_SENT_DUPLICATE")
    void b11_replayOfEventWithDroppedNotification_answersNotSentDuplicate() {
        List<TestEvent> events = overflowTheQueue("B11");
        TestEvent dropped = events.get(CAPACITY); // the first one answered DROPPED_QUEUE_FULL

        assertDuplicateAnswer(post(dropped), dropped.eventId()); // the queue is still full: the answer is about this request

        receiverAnswers(ok());
        awaitNotificationQueueEmpty();
        assertDuplicateAnswer(post(dropped), dropped.eventId()); // and with free places it is the same
    }

    /**
     * The receiver hangs; then {@code CAPACITY + OVERFLOW} events of the default pair are sent one after another.
     * Each must be a 201; the first {@code CAPACITY} answer QUEUED, the rest DROPPED_QUEUE_FULL.
     */
    private List<TestEvent> overflowTheQueue(String casePrefix) {
        receiverAnswers(okAfter(hungDelay()));
        List<TestEvent> events = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        for (int i = 1; i <= CAPACITY + OVERFLOW; i++) {
            TestEvent event = raised(at(i), "MAJOR", casePrefix + "-e" + i);
            ApiResponse response = post(event);
            assertThat(response.status()).as("HTTP status of %s: %s", event, response).isEqualTo(201);
            outcomes.add((String) response.jsonObject().get("notification"));
            events.add(event);
        }
        List<String> expected = new ArrayList<>(Collections.nCopies(CAPACITY, "QUEUED"));
        expected.addAll(Collections.nCopies(OVERFLOW, "DROPPED_QUEUE_FULL"));
        assertThat(outcomes).as("the notification field of the answers, capacity %d, receiver hung", CAPACITY)
                .isEqualTo(expected);
        return events;
    }
}
