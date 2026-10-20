package com.se.telecomalarm.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.se.telecomalarm.api.NotificationRecorder.ReceivedNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E tests: notifications, with WireMock as the receiver. E-07 / E-08 need a queue of capacity 2 and are in
 * {@link NotificationQueueFullTests}; E-14 tests the queue alone and is in {@code NotificationQueueShutdownTests}.
 * Also here: the notification parts of C-06 and C-10 (E-11 holds those of C-20 and C-23).
 * <p>
 * How the tests wait (no {@code Thread.sleep}; delays exist only in WireMock):
 * <ul>
 *   <li>"a notification arrives" - Awaitility polls the {@link NotificationRecorder} until it is there;</li>
 *   <li>"notification_sent = true" - Awaitility polls the database;</li>
 *   <li>"nothing (more) is sent" / "notification_sent stays false" - first wait until the application's queue is
 *       empty ({@code usedQueueCapacity() == 0}), then look once: after that moment nothing can change any more.</li>
 * </ul>
 * Tests that check an absence (E-09, E-12) also send one accepted <i>control</i> event and wait for its
 * notification: with it, "the receiver got only the control" means the rejected event was not notified, and not
 * merely that nothing is sent at all.
 * <p>
 * eventIds start with the case number ({@code E02-e1}), so a notification in a failure message shows where it came from.
 */
class NotificationTests extends AlarmStateTestBase {

    /**
     * E-05 / E-06: one event per element, so a failure of one element does not delay the others.
     */
    private static final List<String> FAILURE_ELEMENTS = List.of("BTS-100", "BTS-200", "BTS-300");

    private static List<String> failedEventIds(ReceiverFailure failure, String casePrefix) {
        List<String> eventIds = new ArrayList<>();
        for (String element : FAILURE_ELEMENTS) {
            eventIds.add(casePrefix + "-" + failure + "-" + element);
        }
        return eventIds;
    }

    // ---- E-01: one notification, its content ----------------------------------------------------------------------

    /**
     * Media type without parameters, lower case; null if the header is absent.
     */
    private static String mediaType(String contentTypeHeader) {
        return contentTypeHeader == null ? null : contentTypeHeader.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    // ---- E-02, E-03, E-04: order, parallelism, the request thread does not wait ------------------------------------

    @ParameterizedTest(name = "description = {0}")
    @NullSource
    @ValueSource(strings = "Cell is down")
    @DisplayName("E-01 an accepted event gives exactly one POST with exactly the fields of the event; notification_sent becomes true after the 2xx")
    void e01_acceptedEvent_givesOneNotificationWithTheFieldsOfTheEvent(String description) {
        TestEvent event = raised(at(0), "CRITICAL", "E01-e1").withDescription(description);

        ApiResponse response = post(event);

        assertStatus(response, 201);
        assertThat(response.jsonObject()).as("body of %s", response)
                .isEqualTo(Map.of("eventId", "E01-e1", "notification", "QUEUED"));
        awaitNotificationSent("E01-e1");
        awaitNotificationQueueEmpty();
        List<ReceivedNotification> received = receivedNotifications();
        assertThat(received).as("POST requests the receiver got").hasSize(1);
        ReceivedNotification notification = received.get(0);
        assertThat(mediaType(notification.contentType())).as("Content-Type of %s", notification).isEqualTo(JSON);
        // The form of an item of /history: the seven fields of the event, "description": null when it has none,
        // and nothing about the state of the pair.
        assertThat(notification.jsonBody()).as("body of %s", notification).isEqualTo(event.asHistoryItem());
    }

    @Test
    @DisplayName("E-02 the example of the assignment: notifications of one element arrive in the order of the events (1, 3, 5)")
    void e02_notificationsOfOneElement_arriveInTheOrderOfTheEvents() {
        // Every answer takes a little time, so notifications that are not kept in order would overtake each other.
        // 1/25 of the read timeout: 200 ms with the production 5 s, as in the test case.
        receiverAnswers(okAfter(readTimeout().dividedBy(25)));
        TestEvent first = raised(at(0), "MAJOR", "E02-e1").forPair("BTS-100", "CELL_DOWN");
        TestEvent second = raised(at(0), "MAJOR", "E02-e2").forPair("BTS-200", "HIGH_CPU");
        TestEvent third = raised(at(0), "MAJOR", "E02-e3").forPair("BTS-100", "CELL_UP");
        TestEvent fourth = raised(at(0), "MAJOR", "E02-e4").forPair("BTS-300", "CONNECTION_LOST");
        TestEvent fifth = raised(at(0), "MAJOR", "E02-e5").forPair("BTS-100", "POWER_FAILURE");

        postAccepted(first, second, third, fourth, fifth);

        awaitNotifications(5);
        awaitNotificationQueueEmpty();
        assertThat(receivedEventIds()).as("every event is notified exactly once")
                .containsExactlyInAnyOrder("E02-e1", "E02-e2", "E02-e3", "E02-e4", "E02-e5");
        assertThat(receivedNotifications().stream()
                           .filter(notification -> "BTS-100".equals(notification.elementId()))
                           .map(ReceivedNotification::eventId))
                .as("order of arrival of the BTS-100 notifications; all arrivals: %s", receivedNotifications())
                .containsExactly("E02-e1", "E02-e3", "E02-e5");
    }

    @Test
    @DisplayName("E-03 a slow receiver for one element does not hold back the notifications of another element")
    void e03_slowElement_doesNotHoldBackAnotherElement() {
        Duration slow = slowButDeliveredDelay();
        receiverAnswersFor("$.elementId", "BTS-100", okAfter(slow));
        TestEvent slowElement = raised(at(0), "MAJOR", "E03-e1").forPair("BTS-100", "CELL_DOWN");
        TestEvent otherElement = raised(at(0), "MAJOR", "E03-e2").forPair("BTS-200", "CELL_DOWN");

        postAccepted(slowElement, otherElement);

        ReceivedNotification slowOne = awaitNotificationOf("E03-e1");
        ReceivedNotification otherOne = awaitNotificationOf("E03-e2");
        // The receiver cannot answer E03-e1 before "arrival + delay". If E03-e2 arrived earlier than that, it was sent
        // while E03-e1 was still waiting for its answer. A sender that serialises across elements would send E03-e2
        // only after that answer, i.e. later than this moment.
        long slowAnswerAt = slowOne.receivedAtNanos() + slow.toNanos();
        assertThat(otherOne.receivedAtNanos())
                .as("arrival of the BTS-200 notification must be before the earliest possible answer to BTS-100 "
                            + "(%d ns after the BTS-100 arrival)", slow.toNanos())
                .isLessThan(slowAnswerAt);
        awaitNotificationSent("E03-e2");
        awaitNotificationSent("E03-e1"); // slow, but within the read timeout: delivered
    }

    // ---- E-05, E-06, E-13: a failing receiver -------------------------------------------------------------------

    @Test
    @DisplayName("E-04 the request thread does not wait for the receiver: 201 comes fast while the receiver hangs")
    void e04_requestThread_doesNotWaitForTheReceiver() {
        warmUp();
        receiverAnswers(okAfter(hungDelay()));

        long start = System.nanoTime();
        ApiResponse response = post(raised(at(0), "MAJOR", "E04-e1"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertStatus(response, 201);
        assertThat(response.jsonObject()).as("body of %s", response)
                .isEqualTo(Map.of("eventId", "E04-e1", "notification", "QUEUED"));
        assertThat(took).as("time to the 201 while the receiver hangs (read timeout %s)", readTimeout())
                .isLessThan(FAST_ANSWER);
        // The HTTP call really went out (and hangs): without it a fast 201 would prove nothing.
        awaitNotificationOf("E04-e1");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReceiverFailure.class)
    @DisplayName("E-05 a failing receiver: every POST is still a fast 201, notification_sent stays false, the application is alive")
    void e05_failingReceiver_doesNotAffectIngestion(ReceiverFailure failure) {
        warmUp();

        List<Duration> answerTimes = sendWhileReceiverFails(failure, "E05");

        assertThat(answerTimes).as("time to each 201 while the receiver fails (%s)", failure)
                .allSatisfy(took -> assertThat(took).isLessThan(FAST_ANSWER));
        awaitFailedSendsFinished(failure, "E05");
        assertNotificationNotSent(failedEventIds(failure, "E05").toArray(String[]::new));
        assertStatus(get("/api/alarms/active"), 200);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReceiverFailure.class)
    @DisplayName("E-06 the senders survive failures: once the receiver answers 200 again, new notifications of the same elements are delivered")
    void e06_afterFailures_newNotificationsAreDelivered(ReceiverFailure failure) {
        sendWhileReceiverFails(failure, "E06");
        awaitFailedSendsFinished(failure, "E06");

        receiverAnswers(ok());
        List<String> nextEventIds = new ArrayList<>();
        for (String element : FAILURE_ELEMENTS) {
            TestEvent next = raised(at(1), "MAJOR", "E06-" + failure + "-next-" + element).forPair(element, "CELL_DOWN");
            postAccepted(next);
            nextEventIds.add(next.eventId());
        }

        for (String eventId : nextEventIds) {
            awaitNotificationSent(eventId);
        }
    }

    // ---- E-09, E-12: no notification for a rejected event ------------------------------------------------------------

    @Test
    @DisplayName("E-13 a 4xx from the receiver: notification_sent stays false and the next notification of the element still goes")
    void e13_clientErrorFromReceiver_nextNotificationStillGoes() {
        receiverAnswersFor("$.eventId", "E13-e1", badRequest());
        TestEvent refused = raised(at(0), "MAJOR", "E13-e1");
        TestEvent next = raised(at(1), "MAJOR", "E13-e2"); // same element: its sender continues after the 400

        postAccepted(refused, next);

        awaitNotificationSent("E13-e2");
        assertNotificationNotSent("E13-e1");
        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly("E13-e1", "E13-e2");
    }

    @Test
    @DisplayName("E-09 a rejected event (CLEARED without a RAISED, 409) is not notified")
    void e09_rejectedEvent_isNotNotified() {
        postRejected(cleared(at(5), "E09-e1")); // also checks O5

        sendControlEventAndAwaitIt("E09-control");

        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly("E09-control");
    }

    // ---- E-10, E-11, C-06, C-10: what is notified ------------------------------------------------------------------

    @Test
    @DisplayName("E-12 a rejected event and its repeat (B-07) give no notification")
    void e12_rejectedEventAndItsRepeat_giveNoNotification() {
        TestEvent rejected = cleared(at(5), "E12-e2");
        postRejected(rejected);

        postRejected(rejected);

        sendControlEventAndAwaitIt("E12-control");
        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly("E12-control");
    }

    @Test
    @DisplayName("E-10 a late (stale) event is notified too, with its own fields, although the state does not change")
    void e10_lateEvent_isNotifiedWithItsOwnFields() {
        TestEvent newer = raised(at(5), "MAJOR", "E10-e1");
        TestEvent late = raised(at(0), "MAJOR", "E10-e2");

        postAccepted(newer, late);

        awaitNotifications(2);
        awaitNotificationQueueEmpty();
        assertThat(receivedNotifications()).extracting(ReceivedNotification::jsonBody)
                .as("notification bodies in the order of arrival")
                .containsExactly(newer.asHistoryItem(), late.asHistoryItem());
    }

    @Test
    @DisplayName("E-11 (C-20) resurrection: the RAISED is notified, then the resurrected CLEARED")
    void e11_c20_resurrection_notifiesRaisedThenResurrectedCleared() {
        TestEvent clearedFirst = cleared(at(5), "E11-C20-e2");
        TestEvent raised = raised(at(0), "MAJOR", "E11-C20-e1");
        postRejected(clearedFirst); // 409 and no notification yet (O5)

        postAccepted(raised);

        awaitNotifications(2);
        awaitNotificationQueueEmpty();
        assertThat(receivedNotifications()).extracting(ReceivedNotification::jsonBody)
                .as("notification bodies in the order of arrival")
                .containsExactly(raised.asHistoryItem(), clearedFirst.asHistoryItem());
    }

    @Test
    @DisplayName("E-11 (C-23) one RAISED resurrects two CLEARED: notifications e1, e2, e3 in time order")
    void e11_c23_resurrectionOfSeveral_notifiesInTimeOrder() {
        TestEvent clearedAt5 = cleared(at(5), "E11-C23-e2");
        TestEvent clearedAt10 = cleared(at(10), "E11-C23-e3");
        TestEvent raised = raised(at(0), "MAJOR", "E11-C23-e1");
        postRejected(clearedAt5);
        postRejected(clearedAt10);

        postAccepted(raised);

        awaitNotifications(3);
        awaitNotificationQueueEmpty();
        assertThat(receivedNotifications()).extracting(ReceivedNotification::jsonBody)
                .as("notification bodies in the order of arrival")
                .containsExactly(raised.asHistoryItem(), clearedAt5.asHistoryItem(), clearedAt10.asHistoryItem());
    }

    @Test
    @DisplayName("C-06 (notification part) the second CLEARED in a row is notified with its own fields")
    void c06_secondClearedInARow_isNotified() {
        TestEvent secondCleared = cleared(at(10), "C06-e3");

        postAccepted(raised(at(0), "MAJOR", "C06-e1"), cleared(at(5), "C06-e2"), secondCleared);

        assertThat(awaitNotificationOf("C06-e3").jsonBody()).isEqualTo(secondCleared.asHistoryItem());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("C-10 (notification part) a late RAISED after the clear is notified with its own fields")
    void c10_lateRaisedAfterClear_isNotified() {
        TestEvent lateRaised = raised(at(2), "MAJOR", "C10-e3");

        postAccepted(raised(at(0), "MAJOR", "C10-e1"), cleared(at(5), "C10-e2"), lateRaised);

        assertThat(awaitNotificationOf("C10-e3").jsonBody()).isEqualTo(lateRaised.asHistoryItem());
    }

    /**
     * One accepted event of an unrelated element, its notification awaited, then the queue drained. Used where a test
     * checks an absence, so that the receiver and the sender are known to work in this very test.
     */
    private void sendControlEventAndAwaitIt(String eventId) {
        postAccepted(raised(at(0), "MAJOR", eventId).forPair("BTS-900", "CONTROL"));
        awaitNotificationOf(eventId);
        awaitNotificationQueueEmpty();
    }

    /**
     * The first request after the application starts pays one-off costs (servlet initialisation, first queries,
     * class loading). A test that measures "the 201 comes fast" runs one ordinary request first, so it measures only
     * what it is about. The receiver is the default one (200) at that point.
     */
    private void warmUp() {
        postAccepted(raised(at(0), "MAJOR", "warm-up").forPair("BTS-WARM-UP", "WARM_UP"));
        awaitNotificationQueueEmpty();
    }

    /**
     * The receiver starts failing in the given way; then one event per element of {@link #FAILURE_ELEMENTS} is sent,
     * each must get 201. Returns how long each 201 took.
     */
    private List<Duration> sendWhileReceiverFails(ReceiverFailure failure, String casePrefix) {
        receiverAnswers(failingAnswer(failure));
        List<Duration> answerTimes = new ArrayList<>();
        List<String> eventIds = failedEventIds(failure, casePrefix);
        for (int i = 0; i < FAILURE_ELEMENTS.size(); i++) {
            TestEvent event = raised(at(0), "MAJOR", eventIds.get(i)).forPair(FAILURE_ELEMENTS.get(i), "CELL_DOWN");
            long start = System.nanoTime();
            ApiResponse response = post(event);
            answerTimes.add(Duration.ofNanos(System.nanoTime() - start));
            assertThat(response.status()).as("HTTP status of %s: %s", event, response).isEqualTo(201);
        }
        return answerTimes;
    }

    /**
     * Every failing send was attempted (the receiver saw it, so the failure path really ran) and finished.
     * With a hung receiver each send ends only at the read timeout; if the application ran them one after another
     * (a pool smaller than the number of elements), that is up to one read timeout per element, so the limit here
     * is longer than the usual one.
     */
    private void awaitFailedSendsFinished(ReceiverFailure failure, String casePrefix) {
        Duration limit = readTimeout().multipliedBy(FAILURE_ELEMENTS.size() + 1);
        awaitNotificationQueueEmpty(limit, "after the failing sends");
        assertThat(receivedEventIds()).as("the receiver saw every failing send")
                .containsAll(failedEventIds(failure, casePrefix));
    }

    private ResponseDefinitionBuilder failingAnswer(ReceiverFailure failure) {
        return switch (failure) {
            case SERVER_ERROR -> serverError();
            case EMPTY_RESPONSE -> aResponse().withFault(Fault.EMPTY_RESPONSE);
            case HUNG -> okAfter(hungDelay());
        };
    }

    /**
     * The three ways the receiver fails in E-05 / E-06.
     * <p>
     * The test case also names {@code Fault.CONNECTION_RESET_BY_PEER}. WireMock documents that this fault "only seems
     * to work properly on *nix OSs" and on Windows "will most likely cause the connection to hang rather than reset";
     * the tests run on Windows too, and a hang is already the HUNG variant. So the broken connection is
     * {@code Fault.EMPTY_RESPONSE}: the receiver closes the connection without sending any response.
     */
    enum ReceiverFailure {
        SERVER_ERROR,
        EMPTY_RESPONSE,
        HUNG
    }
}
