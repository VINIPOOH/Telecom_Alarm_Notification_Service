package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Section C.2 of the test-case spec: events out of time order (rule 2 and the total order of SPEC section 2).
 * <p>
 * The notification part of C-10 ("a notification for e3 is sent") is in {@code NotificationTests}.
 */
class AlarmOutOfOrderTests extends AlarmStateTestBase {

    /**
     * C-16: the eventIds of the set, in time order (see {@link #c16Events()}).
     */
    private static final List<String> C16_EVENT_IDS = List.of("e1", "e2", "e3", "e4", "e5");

    /**
     * C-16: all 5! = 120 arrival orders of the set, each one a separate invocation.
     * Each invocation is a new test for JUnit, so {@code resetState()} of the base class empties the database before
     * it, and the invocation name in the report is the arrival order itself.
     */
    static Stream<Arguments> allArrivalOrdersOfC16() {
        List<List<String>> orders = new ArrayList<>();
        permutations(new ArrayList<>(C16_EVENT_IDS), 0, orders);
        return orders.stream().map(order -> arguments(order));
    }

    /**
     * Adds to {@code out} every permutation of {@code items} that keeps {@code items[0..from)} as it is: each element
     * of the tail in turn is swapped to position {@code from}, the rest is permuted recursively, the swap is undone.
     * Called with {@code from = 0}, it yields all n! orders, each exactly once.
     */
    private static void permutations(List<String> items, int from, List<List<String>> out) {
        if (from == items.size()) {
            out.add(List.copyOf(items));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            Collections.swap(items, from, i);
            permutations(items, from + 1, out);
            Collections.swap(items, from, i);
        }
    }

    @Test
    @DisplayName("C-10 a late RAISED after the clear is accepted but does not make the pair active")
    void lateRaisedAfterClear_isAccepted_butPairStaysCleared() {
        postAccepted(raised(at(0), "MAJOR", "e1"), cleared(at(5), "e2"), raised(at(2), "MAJOR", "e3"));

        assertThat(activeAlarms()).as("/active").isEmpty();
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED));
    }

    @Test
    @DisplayName("C-11 a late CLEARED earlier than the only RAISED is a 409 REJECTED; the RAISED stays active")
    void lateClearedEarlierThanTheOnlyRaised_is409Rejected_raisedStaysActive() {
        TestEvent raised = raised(at(5), "MAJOR", "e1");
        postAccepted(raised);

        postRejected(cleared(at(0), "e2"));

        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(raised));
    }

    @Test
    @DisplayName("C-12 a late RAISED while a newer one is active is accepted and changes nothing")
    void lateRaisedWhileNewerIsActive_isAccepted_activeRecordUnchanged() {
        TestEvent newer = raised(at(5), "CRITICAL", "e1");

        postAccepted(newer, raised(at(0), "INFO", "e2"));

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(newer));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
    }

    @ParameterizedTest(name = "RAISED arrives first: {0}")
    @ValueSource(booleans = { true, false })
    @DisplayName("C-13 RAISED and CLEARED at the same millisecond: RAISED wins, CLEARED is a 409 REJECTED in both orders")
    void raisedAndClearedAtSameMillisecond_raisedWins_clearedRejectedInBothOrders(boolean raisedFirst) {
        TestEvent raised = raised(at(0), "MAJOR", "e1");
        TestEvent cleared = cleared(at(0), "e2");

        if (raisedFirst) {
            postAccepted(raised);
            postRejected(cleared);
        } else {
            postRejected(cleared);
            postAccepted(raised); // must not resurrect e2: e2 is not later than e1 by the total order
        }

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(raised));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
    }

    @ParameterizedTest(name = "CRITICAL arrives first: {0}")
    @ValueSource(booleans = { true, false })
    @DisplayName("C-14 two RAISED at the same millisecond: the higher severity wins in both orders")
    void twoRaisedAtSameMillisecond_higherSeverityWins(boolean criticalFirst) {
        TestEvent major = raised(at(0), "MAJOR", "e1");
        TestEvent critical = raised(at(0), "CRITICAL", "e2");

        if (criticalFirst) {
            postAccepted(critical, major);
        } else {
            postAccepted(major, critical);
        }

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(critical));
    }

    @ParameterizedTest(name = "e-b arrives first: {0}")
    @ValueSource(booleans = { true, false })
    @DisplayName("C-15 full tie (time and severity): the greater eventId as a string wins in both orders")
    void fullTie_greaterEventIdWins(boolean greaterFirst) {
        TestEvent smaller = raised(at(0), "MAJOR", "e-a");
        TestEvent greater = raised(at(0), "MAJOR", "e-b");

        if (greaterFirst) {
            postAccepted(greater, smaller);
        } else {
            postAccepted(smaller, greater);
        }

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(greater));
    }

    @ParameterizedTest(name = "arrival order {0}")
    @MethodSource("allArrivalOrdersOfC16")
    @DisplayName("C-16 the final state and the visible history do not depend on the arrival order (all 120 orders)")
    void finalStateAndVisibleHistory_doNotDependOnArrivalOrder(List<String> arrivalOrder) {
        Map<String, TestEvent> events = c16Events();
        List<TestEvent> arrival = arrivalOrder.stream().map(events::get).toList();
        // The answer to each single request does depend on what has already arrived: computed from the rules.
        List<Integer> expectedStatuses = ExpectedAlarmState.sequentialStatuses(arrival);

        for (int i = 0; i < arrival.size(); i++) {
            TestEvent event = arrival.get(i);
            ApiResponse response = post(event);
            assertThat(response.status())
                    .as("arrival order %s, request %d of %d, %s: HTTP status of %s",
                        arrivalOrder, i + 1, arrival.size(), event, response)
                    .isEqualTo(expectedStatuses.get(i));
            if (response.status() == 409) {
                ProblemAssertions.assertProblem(response, 409);
            }
        }

        // The final state is written by hand from the test case, not taken from ExpectedAlarmState.
        assertThat(activeAlarms())
                .as("arrival order %s: /active", arrivalOrder)
                .containsExactly(ActiveAlarm.of(events.get("e5")));
        assertThat(storedResults())
                .as("arrival order %s: results in alarm_events", arrivalOrder)
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED, "e4", ACCEPTED, "e5", ACCEPTED));
        assertThat(visibleHistory())
                .as("arrival order %s: /history items", arrivalOrder)
                .isEqualTo(C16_EVENT_IDS.stream().map(id -> events.get(id).asHistoryItem()).toList());
    }

    /**
     * R(10:00, MAJOR), C(10:05), R(10:07, CRITICAL), C(10:10), R(10:12, WARNING); eventIds e1..e5 in this order.
     */
    private Map<String, TestEvent> c16Events() {
        Map<String, TestEvent> events = new LinkedHashMap<>();
        for (TestEvent event : List.of(
                raised(at(0), "MAJOR", "e1"),
                cleared(at(5), "e2"),
                raised(at(7), "CRITICAL", "e3"),
                cleared(at(10), "e4"),
                raised(at(12), "WARNING", "e5"))) {
            events.put(event.eventId(), event);
        }
        return events;
    }
}
