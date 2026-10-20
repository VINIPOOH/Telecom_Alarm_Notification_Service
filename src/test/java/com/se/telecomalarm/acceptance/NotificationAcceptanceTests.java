package com.se.telecomalarm.acceptance;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.se.telecomalarm.api.AlarmStateTestBase;
import com.se.telecomalarm.api.ApiResponse;
import com.se.telecomalarm.api.NotificationRecorder.ReceivedNotification;
import com.se.telecomalarm.api.TestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance checks of the assignment (section 7) for the notifications: one test per check, written as
 * given / when / then. The receiver is WireMock over HTTPS.
 * <p>
 * These tests check exactly what the assignment asks and nothing more. The detailed cases (the content of a
 * notification, every way the receiver can fail, a full queue, resurrection) are in {@code NotificationTests} and
 * {@code NotificationQueueFullTests}.
 * <p>
 * No sleeps: delays exist only in WireMock's answers, and the tests wait with Awaitility.
 */
@DisplayName("Assignment, section 7: notifications")
class NotificationAcceptanceTests extends AlarmStateTestBase {

    @Test
    @DisplayName("7.4 notifications of one element arrive in the order of its accepted events (the example of section 8: 1, 3, 5)")
    void givenTheExampleSequence_whenItsEventsArePosted_thenTheNotificationsOfBts100ArriveInOrder() {
        // given: every answer takes a little time, so notifications that are not kept in order would overtake each other
        receiverAnswers(okAfter(readTimeout().dividedBy(25)));
        List<TestEvent> sequence = List.of(
                raisedFor("BTS-100", "CELL_DOWN", "EVT-1"),
                raisedFor("BTS-200", "HIGH_CPU", "EVT-2"),
                raisedFor("BTS-100", "CELL_UP", "EVT-3"),
                raisedFor("BTS-300", "CONNECTION_LOST", "EVT-4"),
                raisedFor("BTS-100", "POWER_FAILURE", "EVT-5"));

        // when
        postAccepted(sequence.toArray(TestEvent[]::new));

        // then
        awaitNotifications(sequence.size());
        awaitNotificationQueueEmpty();
        assertThat(receivedEventIds()).as("every event is notified exactly once")
                .containsExactlyInAnyOrder("EVT-1", "EVT-2", "EVT-3", "EVT-4", "EVT-5");
        assertThat(eventIdsReceivedFor("BTS-100")).as("order of arrival of the BTS-100 notifications")
                .containsExactly("EVT-1", "EVT-3", "EVT-5");
    }

    @Test
    @DisplayName("7.4 notifications of different elements are sent in parallel: a slow element does not hold back another")
    void givenSlowReceiverForOneElement_whenTwoElementsRaiseAlarms_thenTheOtherElementIsNotifiedWithoutWaiting() {
        // given
        Duration slow = slowButDeliveredDelay();
        receiverAnswersFor("$.elementId", "BTS-100", okAfter(slow));
        TestEvent slowElement = raisedFor("BTS-100", "CELL_DOWN", "EVT-1");
        TestEvent otherElement = raisedFor("BTS-200", "CELL_DOWN", "EVT-2");

        // when
        postAccepted(slowElement, otherElement);

        // then: the receiver cannot answer EVT-1 earlier than its arrival plus the delay. EVT-2 arriving before that
        // moment means it was sent while EVT-1 was still waiting for its answer.
        ReceivedNotification slowOne = awaitNotificationOf("EVT-1");
        ReceivedNotification otherOne = awaitNotificationOf("EVT-2");
        assertThat(otherOne.receivedAtNanos())
                .as("arrival of the BTS-200 notification, compared with the earliest possible answer to BTS-100")
                .isLessThan(slowOne.receivedAtNanos() + slow.toNanos());
    }

    @ParameterizedTest(name = "the receiver {0}")
    @EnumSource(UnavailableReceiver.class)
    @DisplayName("7.5 an unavailable receiver does not block alarm intake: every event is accepted at once")
    void givenUnavailableReceiver_whenEventsArePosted_thenEachIsAcceptedWithoutWaitingForTheReceiver(
            UnavailableReceiver receiverState) {
        // given
        warmUp();
        receiverAnswers(receiverState.answer(hungDelay()));
        List<TestEvent> events = List.of(
                raisedFor("BTS-100", "CELL_DOWN", "EVT-1"),
                raisedFor("BTS-200", "CELL_DOWN", "EVT-2"),
                raisedFor("BTS-300", "CELL_DOWN", "EVT-3"));

        for (TestEvent event : events) {
            // when
            long start = System.nanoTime();
            ApiResponse response = post(event);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            // then
            assertStatus(response, 201);
            assertThat(took).as("time to the 201 of %s while the receiver %s", event.eventId(), receiverState)
                    .isLessThan(FAST_ANSWER);
        }
        assertThat(storedResults()).as("every event is stored as accepted")
                .containsAllEntriesOf(Map.of("EVT-1", ACCEPTED, "EVT-2", ACCEPTED, "EVT-3", ACCEPTED));
    }

    /**
     * A RAISED of the given alarm at 10:00.
     */
    private TestEvent raisedFor(String elementId, String alarmCode, String eventId) {
        return raised(at(0), "MAJOR", eventId).forPair(elementId, alarmCode);
    }

    /**
     * The eventIds of one element's notifications, in the order the receiver got them.
     */
    private List<String> eventIdsReceivedFor(String elementId) {
        return receivedNotifications().stream()
                .filter(notification -> elementId.equals(notification.elementId()))
                .map(ReceivedNotification::eventId)
                .toList();
    }

    /**
     * The first request after the application starts pays one-off costs (servlet initialisation, first queries,
     * class loading). A test that measures "the answer comes at once" sends one ordinary event first, so it measures
     * only what it is about.
     */
    private void warmUp() {
        postAccepted(raisedFor("BTS-WARM-UP", "WARM_UP", "warm-up"));
        awaitNotificationQueueEmpty();
    }

    /**
     * How the receiver is unavailable in 7.5.
     */
    enum UnavailableReceiver {

        /**
         * Accepts the connection and never answers in time: the sender waits up to its read timeout.
         */
        HANGS {
            @Override
            ResponseDefinitionBuilder answer(Duration hungDelay) {
                return okAfter(hungDelay);
            }
        },

        /**
         * Closes the connection without any answer.
         */
        DROPS_THE_CONNECTION {
            @Override
            ResponseDefinitionBuilder answer(Duration hungDelay) {
                return aResponse().withFault(Fault.EMPTY_RESPONSE);
            }
        };

        abstract ResponseDefinitionBuilder answer(Duration hungDelay);
    }
}
