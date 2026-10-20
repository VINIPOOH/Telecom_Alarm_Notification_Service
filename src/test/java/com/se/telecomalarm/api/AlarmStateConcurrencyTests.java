package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C tests: concurrent requests must not corrupt the state (the assignment, section 3).
 * <p>
 * How the requests really run at the same time: {@link SimultaneousRequests} gives every request its own thread
 * and holds all threads at one barrier, so nobody sends until the last thread is ready; then all are released
 * together. The service sees them as overlapping requests; whatever the service does to keep the events of one
 * pair in order (the per-pair advisory lock, the per-element JVM lock) is what is under test.
 * <p>
 * How the expected state is computed: by {@link ExpectedAlarmState}, a closed formula over the whole set of events
 * written from the state rules B1-B6 (not the application's step-by-step algorithm). With concurrent
 * requests the arrival order is unknown, but the final state must not depend on it; the formula needs no order.
 * The answer to each single request DOES depend on the order, so only its allowed range is checked: a RAISED is
 * always 201, a CLEARED is 201 or 409.
 * <p>
 * A passing run does not prove there is no race: a race shows up only when the threads happen to interleave inside
 * its window. That is why every scenario is repeated with an empty database; random data and send orders come from
 * fixed seeds, so a failing round can be rebuilt from the seed in its message.
 */
class AlarmStateConcurrencyTests extends AlarmStateTestBase {

    private static final List<String> SEVERITIES = List.of("INFO", "WARNING", "MAJOR", "CRITICAL");

    /**
     * C-30: events of one pair sent at the same moment (one thread each), and how many times the scenario runs.
     */
    private static final int BURST_EVENTS = 50;
    private static final int BURST_ROUNDS = 20;
    /**
     * C-30: seed of round n is BURST_SEED + n.
     */
    private static final long BURST_SEED = 30_000L;

    /**
     * C-31: how many times the two-request race runs.
     */
    private static final int NEW_PAIR_RACE_ROUNDS = 100;

    /**
     * C-32: pairs of one element raised at the same moment, and how many times the scenario runs.
     */
    private static final int SAME_ELEMENT_PAIRS = 10;
    private static final int SAME_ELEMENT_ROUNDS = 10;

    /**
     * C-33: 5 elements x 4 alarm codes = 20 pairs, 10 events each = 200 events sent at the same moment.
     */
    private static final int MIXED_ELEMENTS = 5;
    private static final int MIXED_CODES_PER_ELEMENT = 4;
    private static final int MIXED_EVENTS_PER_PAIR = 10;
    private static final int MIXED_EVENTS = MIXED_ELEMENTS * MIXED_CODES_PER_ELEMENT * MIXED_EVENTS_PER_PAIR;
    private static final int MIXED_ROUNDS = 5;
    /**
     * C-33: seed of round n is MIXED_SEED + n.
     */
    private static final long MIXED_SEED = 33_000L;
    /**
     * C-33: timestamps of a pair are drawn from this many one-second slots, so equal timestamps are frequent and the
     * tie rules (2)-(4) of the total order are exercised under concurrency too.
     */
    private static final int MIXED_TIME_SLOTS = 6;

    // ---- C-30 -------------------------------------------------------------------------------------------------------

    /**
     * The answer to one request of a parallel run, without knowing the arrival order: a RAISED is always 201
     * (an outdated one is accepted too); a CLEARED is 201 if an earlier RAISED of its pair was already committed,
     * otherwise 409 with a valid problem body. Anything else (500, 400, a timeout) is a failure.
     */
    private static void assertEveryAnswerIsAllowed(List<TestEvent> sent, List<ApiResponse> responses, String context) {
        for (int i = 0; i < sent.size(); i++) {
            TestEvent event = sent.get(i);
            ApiResponse response = responses.get(i);
            if (event.isRaised()) {
                assertThat(response.status()).as("HTTP status of %s: %s; %s", event, response, context).isEqualTo(201);
            } else {
                assertThat(response.status()).as("HTTP status of %s: %s; %s", event, response, context).isIn(201, 409);
                if (response.status() == 409) {
                    ProblemAssertions.assertProblem(response, 409);
                }
            }
        }
    }

    private static List<TestEvent> shuffled(List<TestEvent> events, Random random) {
        List<TestEvent> copy = new ArrayList<>(events);
        Collections.shuffle(copy, random);
        return copy;
    }

    // ---- C-31 -------------------------------------------------------------------------------------------------------

    private static String randomSeverity(Random random) {
        return SEVERITIES.get(random.nextInt(SEVERITIES.size()));
    }

    // ---- C-32 -------------------------------------------------------------------------------------------------------

    private static List<String> eventIds(List<TestEvent> events) {
        return events.stream().map(TestEvent::eventId).toList();
    }

    // ---- C-33 -------------------------------------------------------------------------------------------------------

    @RepeatedTest(value = BURST_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("C-30 a burst of 50 events of one pair in parallel ends in the state of sequential processing")
    void burstOfOnePairInParallel_endsInStateOfSequentialProcessing(RepetitionInfo repetition) {
        int round = repetition.getCurrentRepetition();
        long seed = BURST_SEED + round;
        Random random = new Random(seed);
        // Even rounds end with a RAISED (the pair must end active with exactly that record), odd rounds with a CLEARED
        // (the pair must end not active): both endings are covered whatever the random part draws.
        List<TestEvent> events = burstOfOnePair(random, round % 2 == 0);
        List<TestEvent> sendOrder = shuffled(events, random);
        String context = "round " + round + ", seed " + seed + ", send order " + eventIds(sendOrder);
        assertThat(ExpectedAlarmState.finalResults(events).values())
                .as("test data (C-30: every CLEARED has an earlier RAISED in the set), %s", context)
                .containsOnly(ACCEPTED);

        List<ApiResponse> responses = sendAllAtOnce(sendOrder);

        assertEveryAnswerIsAllowed(sendOrder, responses, context);
        assertThat(db.history()).as("rows of alarm_events, %s", context).hasSize(BURST_EVENTS);
        assertThat(storedResults()).as("results in alarm_events, %s", context)
                .isEqualTo(ExpectedAlarmState.finalResults(events));
        // Equality with a list of at most one record also checks "at most one active record of the pair".
        assertThat(activeAlarms()).as("/active, %s", context).isEqualTo(ExpectedAlarmState.finalActive(events));
    }

    /**
     * 50 events of the default pair, one second apart (all timestamps differ). The earliest is a RAISED, so every
     * CLEARED has an earlier RAISED in the set; the last one is a RAISED or a CLEARED as asked; the rest are drawn.
     * Every event has its own description, so a record mixing fields of two events is caught.
     */
    private List<TestEvent> burstOfOnePair(Random random, boolean endsWithRaised) {
        List<TestEvent> events = new ArrayList<>();
        for (int i = 0; i < BURST_EVENTS; i++) {
            long timestamp = t0 + i * SECOND;
            String eventId = String.format("b%02d", i);
            boolean isRaised = i == 0 || (i == BURST_EVENTS - 1 ? endsWithRaised : random.nextBoolean());
            TestEvent event = isRaised ? raised(timestamp, randomSeverity(random), eventId) : cleared(timestamp, eventId);
            events.add(event.withDescription("burst " + eventId));
        }
        return events;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    @RepeatedTest(value = NEW_PAIR_RACE_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("C-31 race on a new pair: CLEARED and its earlier RAISED in parallel always end both accepted, not active")
    void clearedAndItsEarlierRaisedOnNewPairInParallel_alwaysEndBothAccepted_pairNotActive(RepetitionInfo repetition) {
        TestEvent raised = raised(at(0), "MAJOR", "e1");
        TestEvent cleared = cleared(at(5), "e2");
        // The thread of the first task is started first; alternate it so neither request has a head start every time.
        List<TestEvent> sendOrder = repetition.getCurrentRepetition() % 2 == 0
                                    ? List.of(raised, cleared)
                                    : List.of(cleared, raised);

        List<ApiResponse> responses = sendAllAtOnce(sendOrder);

        String context = "send order " + eventIds(sendOrder) + ", responses " + responses;
        assertEveryAnswerIsAllowed(sendOrder, responses, context);
        // Whoever came first: either e2 was accepted after e1 was committed, or e2 was rejected and e1 resurrected it.
        assertThat(storedResults()).as("results in alarm_events, %s", context)
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
        assertThat(activeAlarms()).as("/active, %s", context).isEmpty();
    }

    @RepeatedTest(value = SAME_ELEMENT_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("C-32 RAISED for 10 pairs of one element in parallel: all 10 are active")
    void raisedForTenPairsOfOneElementInParallel_allTenActive() {
        List<TestEvent> events = new ArrayList<>();
        for (int i = 0; i < SAME_ELEMENT_PAIRS; i++) {
            events.add(raised(at(0), "MAJOR", "p" + i)
                               .forPair("BTS-100", "CODE-" + i)
                               .withDescription("pair " + i));
        }

        List<ApiResponse> responses = sendAllAtOnce(events);

        for (int i = 0; i < events.size(); i++) {
            assertThat(responses.get(i).status())
                    .as("HTTP status of %s: %s", events.get(i), responses.get(i))
                    .isEqualTo(201);
        }
        // CODE-0 .. CODE-9 sort as strings in creation order, so the expected list is the events in order.
        assertThat(activeAlarms()).isEqualTo(events.stream().map(ActiveAlarm::of).toList());
        assertThat(storedResults()).hasSize(SAME_ELEMENT_PAIRS).containsOnlyKeys(eventIds(events).toArray(String[]::new))
                .allSatisfy((eventId, result) -> assertThat(result).as("result of %s", eventId).isEqualTo(ACCEPTED));
    }

    @RepeatedTest(value = MIXED_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("C-33 200 events of 20 pairs in parallel: no history row is lost, /active matches the total order")
    void manyEventsOfManyPairsInParallel_loseNoHistory_activeMatchesTotalOrder(RepetitionInfo repetition) {
        int round = repetition.getCurrentRepetition();
        long seed = MIXED_SEED + round;
        Random random = new Random(seed);
        List<TestEvent> events = mixedEvents(random);
        List<TestEvent> sendOrder = shuffled(events, random);
        // The events and the send order are rebuilt from the seed alone (only t0 shifts the timestamps).
        String context = "round " + round + ", seed " + seed;

        List<ApiResponse> responses = sendAllAtOnce(sendOrder);

        assertEveryAnswerIsAllowed(sendOrder, responses, context);
        assertThat(db.history()).as("rows of alarm_events, %s", context).hasSize(MIXED_EVENTS);
        assertThat(storedResults()).as("results in alarm_events, %s", context)
                .isEqualTo(ExpectedAlarmState.finalResults(events));
        assertThat(activeAlarms()).as("/active, %s", context).isEqualTo(ExpectedAlarmState.finalActive(events));
    }

    /**
     * Elements BTS-1..BTS-5, codes CODE-1..CODE-4 of each: pairs of one element contend for the per-element lock,
     * pairs of different elements do not. Status, severity and the time slot of every event are drawn, so some pairs
     * end active, some not, and some CLEARED have no earlier RAISED (they must end REJECTED, others resurrected).
     */
    private List<TestEvent> mixedEvents(Random random) {
        List<TestEvent> events = new ArrayList<>();
        int number = 0;
        for (int element = 1; element <= MIXED_ELEMENTS; element++) {
            for (int code = 1; code <= MIXED_CODES_PER_ELEMENT; code++) {
                for (int k = 0; k < MIXED_EVENTS_PER_PAIR; k++) {
                    long timestamp = t0 + random.nextInt(MIXED_TIME_SLOTS) * SECOND;
                    String eventId = String.format("m%03d", number++);
                    TestEvent event = random.nextBoolean()
                                      ? raised(timestamp, randomSeverity(random), eventId)
                                      : cleared(timestamp, eventId);
                    events.add(event.forPair("BTS-" + element, "CODE-" + code).withDescription("mixed " + eventId));
                }
            }
        }
        return events;
    }

    /**
     * Sends every event in its own thread, all released at the same moment; the answers come back in the given order.
     */
    private List<ApiResponse> sendAllAtOnce(List<TestEvent> events) {
        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (TestEvent event : events) {
            requests.add(() -> post(event));
        }
        return SimultaneousRequests.runTogether(requests);
    }
}
