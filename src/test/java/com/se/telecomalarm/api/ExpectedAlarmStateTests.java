package com.se.telecomalarm.api;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.se.telecomalarm.api.ExpectedAlarmState.ACCEPTED;
import static com.se.telecomalarm.api.ExpectedAlarmState.REJECTED;
import static com.se.telecomalarm.api.ExpectedAlarmState.TOTAL_ORDER;
import static com.se.telecomalarm.api.TestEvent.cleared;
import static com.se.telecomalarm.api.TestEvent.raised;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the test-side reference model {@link ExpectedAlarmState} against answers worked out by hand from the
 * test-case spec. The concurrent tests of section C trust this model for their expected state, so a mistake in it
 * would make them check the wrong thing; this class catches that without Spring or a database.
 * <p>
 * It tests test code, not the application, so it is green from the start.
 */
class ExpectedAlarmStateTests {

    /**
     * "10:00" of the test cases; only differences matter.
     */
    private static final long T0 = 1_791_800_000_000L;
    private static final long MINUTE = 60_000L;

    private static long at(int minutes) {
        return T0 + minutes * MINUTE;
    }

    @Test
    @DisplayName("total order: each of the four rules on its own")
    void totalOrder_eachRule() {
        // (1) greater timestamp is later, whatever the status and severity
        assertThat(TOTAL_ORDER.compare(cleared(at(5), "a"), raised(at(0), "CRITICAL", "z"))).isPositive();
        // (2) equal timestamps: RAISED is later than CLEARED
        assertThat(TOTAL_ORDER.compare(raised(at(0), "INFO", "a"), cleared(at(0), "z"))).isPositive();
        // (3) equal timestamps, both RAISED: higher severity is later
        assertThat(TOTAL_ORDER.compare(raised(at(0), "CRITICAL", "a"), raised(at(0), "MAJOR", "z"))).isPositive();
        assertThat(TOTAL_ORDER.compare(raised(at(0), "WARNING", "a"), raised(at(0), "INFO", "z"))).isPositive();
        // (4) all equal: the greater eventId as a string; "e-10" < "e-9" as strings
        assertThat(TOTAL_ORDER.compare(raised(at(0), "MAJOR", "e-b"), raised(at(0), "MAJOR", "e-a"))).isPositive();
        assertThat(TOTAL_ORDER.compare(raised(at(0), "MAJOR", "e-10"), raised(at(0), "MAJOR", "e-9"))).isNegative();
        // two CLEARED at the same time: severity is not looked at, only the eventId
        assertThat(TOTAL_ORDER.compare(cleared(at(0), "e-b"), cleared(at(0), "e-a"))).isPositive();
    }

    @Test
    @DisplayName("C-11: a late CLEARED earlier than the only RAISED is rejected; the RAISED stays active")
    void lateClearedBeforeTheOnlyRaised() {
        TestEvent r = raised(at(5), "MAJOR", "e1");
        TestEvent c = cleared(at(0), "e2");

        assertThat(ExpectedAlarmState.finalResults(List.of(r, c))).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
        assertThat(ExpectedAlarmState.finalActive(List.of(r, c))).containsExactly(ActiveAlarm.of(r));
        assertThat(ExpectedAlarmState.sequentialStatuses(List.of(r, c))).containsExactly(201, 409);
    }

    @Test
    @DisplayName("C-13: CLEARED at the same millisecond as RAISED is rejected in both arrival orders")
    void tieOfClearedAndRaised() {
        TestEvent r = raised(at(0), "MAJOR", "e1");
        TestEvent c = cleared(at(0), "e2");

        assertThat(ExpectedAlarmState.finalResults(List.of(r, c))).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
        assertThat(ExpectedAlarmState.finalActive(List.of(c, r))).containsExactly(ActiveAlarm.of(r));
        assertThat(ExpectedAlarmState.sequentialStatuses(List.of(r, c))).containsExactly(201, 409);
        assertThat(ExpectedAlarmState.sequentialStatuses(List.of(c, r))).containsExactly(409, 201);
    }

    @Test
    @DisplayName("C-20 / C-23: CLEARED events that arrived before their earlier RAISED end up accepted")
    void resurrection() {
        TestEvent r = raised(at(0), "MAJOR", "e1");
        TestEvent c1 = cleared(at(5), "e2");
        TestEvent c2 = cleared(at(10), "e3");

        assertThat(ExpectedAlarmState.finalResults(List.of(c1, c2, r)))
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED));
        assertThat(ExpectedAlarmState.finalActive(List.of(c1, c2, r))).isEmpty();
        assertThat(ExpectedAlarmState.sequentialStatuses(List.of(c1, c2, r))).containsExactly(409, 409, 201);
    }

    @Test
    @DisplayName("C-22 / C-25: a CLEARED earlier than every RAISED stays rejected")
    void clearedEarlierThanEveryRaisedStaysRejected() {
        TestEvent r1 = raised(at(0), "MAJOR", "e1");
        TestEvent c = cleared(at(-60), "e2");
        TestEvent r2 = raised(at(10), "CRITICAL", "e3");

        assertThat(ExpectedAlarmState.finalResults(List.of(r1, c, r2)))
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED, "e3", ACCEPTED));
        assertThat(ExpectedAlarmState.finalActive(List.of(r1, c, r2))).containsExactly(ActiveAlarm.of(r2));
    }

    @Test
    @DisplayName("C-16 set: active with the last RAISED (WARNING), everything accepted")
    void c16Set() {
        List<TestEvent> events = List.of(
                raised(at(0), "MAJOR", "e1"), cleared(at(5), "e2"), raised(at(7), "CRITICAL", "e3"),
                cleared(at(10), "e4"), raised(at(12), "WARNING", "e5"));

        assertThat(ExpectedAlarmState.finalResults(events)).containsOnlyKeys("e1", "e2", "e3", "e4", "e5")
                .allSatisfy((eventId, result) -> assertThat(result).isEqualTo(ACCEPTED));
        assertThat(ExpectedAlarmState.finalActive(events)).containsExactly(ActiveAlarm.of(events.get(4)));
    }

    @Test
    @DisplayName("pairs are independent and the active list is sorted by elementId, then alarmCode")
    void pairsAreIndependent() {
        TestEvent a = raised(at(0), "MAJOR", "e1").forPair("BTS-200", "CELL_DOWN");
        TestEvent b = raised(at(0), "MAJOR", "e2").forPair("BTS-100", "HIGH_CPU");
        TestEvent cOtherPair = cleared(at(5), "e3"); // (BTS-100, CELL_DOWN): no RAISED of its own pair

        assertThat(ExpectedAlarmState.finalResults(List.of(a, b, cOtherPair)))
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", REJECTED));
        assertThat(ExpectedAlarmState.finalActive(List.of(a, b, cOtherPair)))
                .containsExactly(ActiveAlarm.of(b), ActiveAlarm.of(a));
    }
}
