package com.se.telecomalarm.api;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C tests: basic state transitions, events arriving in time order.
 * <p>
 * The notification part of C-06 ("a notification for e3 is sent") is in {@code NotificationTests}.
 */
class AlarmStateTests extends AlarmStateTestBase {

    @Test
    @DisplayName("C-01 RAISED then CLEARED: the pair is not active, both events are accepted")
    void raisedThenCleared_pairIsNotActive_bothAccepted() {
        postAccepted(raised(at(0), "MAJOR", "e1"), cleared(at(5), "e2"));

        assertThat(activeAlarms()).as("/active").isEmpty();
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
    }

    @Test
    @DisplayName("C-02 CLEARED for a pair that never had a RAISED: 409, stored as REJECTED, not in the visible history")
    void clearedForUnknownPair_is409_storedAsRejected_notInVisibleHistory() {
        postRejected(cleared(at(5), "e2"));

        assertThat(storedResults()).isEqualTo(Map.of("e2", REJECTED));
        assertThat(visibleHistory()).as("/history items").isEmpty();
        assertThat(activeAlarms()).as("/active").isEmpty();
    }

    @Test
    @DisplayName("C-03 a later RAISED updates the active record of the pair")
    void laterRaised_updatesActiveRecord() {
        TestEvent first = raised(at(0), "MAJOR", "e1");
        TestEvent second = raised(at(5), "CRITICAL", "e2");

        postAccepted(first, second);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(second));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
    }

    @Test
    @DisplayName("C-04 a later RAISED with a lower severity wins: the last event counts, not the highest severity")
    void laterRaisedWithLowerSeverity_wins() {
        TestEvent critical = raised(at(0), "CRITICAL", "e1");
        TestEvent info = raised(at(5), "INFO", "e2");

        postAccepted(critical, info);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(info));
    }

    @Test
    @DisplayName("C-05 RAISED after the pair was cleared makes it active again")
    void raisedAfterClear_makesPairActiveAgain() {
        TestEvent again = raised(at(10), "WARNING", "e3");

        postAccepted(raised(at(0), "MAJOR", "e1"), cleared(at(5), "e2"), again);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(again));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED));
    }

    @Test
    @DisplayName("C-06 a second CLEARED in a row is accepted: an accepted RAISED is earlier than it")
    void secondClearedInARow_isAccepted() {
        postAccepted(raised(at(0), "MAJOR", "e1"), cleared(at(5), "e2"), cleared(at(10), "e3"));

        assertThat(activeAlarms()).as("/active").isEmpty();
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED));
    }

    @Test
    @DisplayName("C-07 pairs are independent: clearing one pair leaves the others active")
    void pairsAreIndependent_clearingOneLeavesOthersActive() {
        TestEvent cellDown100 = raised(at(0), "MAJOR", "e1");
        TestEvent highCpu100 = raised(at(0), "MAJOR", "e2").forPair("BTS-100", "HIGH_CPU");
        TestEvent cellDown200 = raised(at(0), "MAJOR", "e3").forPair("BTS-200", "CELL_DOWN");

        postAccepted(cellDown100, highCpu100, cellDown200, cleared(at(5), "e4"));

        // Sorted by elementId, then alarmCode.
        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(highCpu100), ActiveAlarm.of(cellDown200));
    }

    @Test
    @DisplayName("C-08 description comes from the last accepted event: a later RAISED without one shows null")
    void descriptionComesFromLastAcceptedEvent() {
        TestEvent withDescription = raised(at(0), "MAJOR", "e1").withDescription("a");
        TestEvent withoutDescription = raised(at(5), "MAJOR", "e2").withDescription(null);

        postAccepted(withDescription, withoutDescription);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(withoutDescription));
        assertThat(activeAlarmOf("BTS-100", "CELL_DOWN")).hasValueSatisfying(
                alarm -> assertThat(alarm.description()).as("description of the active record").isNull());
    }
}
