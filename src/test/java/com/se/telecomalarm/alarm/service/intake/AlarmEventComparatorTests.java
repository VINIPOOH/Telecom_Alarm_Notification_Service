package com.se.telecomalarm.alarm.service.intake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.se.telecomalarm.alarm.domain.AlarmSeverity.CRITICAL;
import static com.se.telecomalarm.alarm.domain.AlarmSeverity.INFO;
import static com.se.telecomalarm.alarm.domain.AlarmSeverity.MAJOR;
import static com.se.telecomalarm.alarm.domain.AlarmSeverity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The G tests: the total order of events of one pair (rules B1, B2), no Spring, no database.
 * {@code compare(a, b) > 0} reads "a is later than b".
 */
class AlarmEventComparatorTests {

    /**
     * The bean has no dependencies, so the test creates it itself.
     */
    private static final AlarmEventComparator ORDER = new AlarmEventComparator();

    /**
     * "10:00"; only differences matter.
     */
    private static final long T0 = 1_791_800_000_000L;

    private static AlarmEvent raised(long timestamp, AlarmSeverity severity, String eventId) {
        return new AlarmEvent(eventId, "BTS-1", "CELL_DOWN", severity, AlarmStatus.RAISED, timestamp, null);
    }

    private static AlarmEvent cleared(long timestamp, AlarmSeverity severity, String eventId) {
        return new AlarmEvent(eventId, "BTS-1", "CELL_DOWN", severity, AlarmStatus.CLEARED, timestamp, null);
    }

    // ---- G-01 ---------------------------------------------------------------------------------------------------

    /**
     * 60 events with unique eventIds over 4 timestamps, so ties of every kind are frequent.
     */
    private static List<AlarmEvent> randomEvents(long seed) {
        Random random = new Random(seed);
        AlarmSeverity[] severities = AlarmSeverity.values();
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long timestamp = T0 + random.nextInt(4);
            AlarmSeverity severity = severities[random.nextInt(severities.length)];
            String eventId = "x" + random.nextInt(1000) + "-" + i; // unique: the suffix
            events.add(random.nextBoolean() ? raised(timestamp, severity, eventId) : cleared(timestamp, severity, eventId));
        }
        return events;
    }

    @Test
    @DisplayName("G-01 rule 1: the greater timestamp is later, whatever the status, severity and eventId")
    void g01_greaterTimestampIsLater() {
        assertThat(ORDER.compare(cleared(T0 + 1, INFO, "a"), raised(T0, CRITICAL, "z"))).isPositive();
        assertThat(ORDER.compare(raised(T0, CRITICAL, "z"), cleared(T0 + 1, INFO, "a"))).isNegative();
        assertThat(ORDER.compare(raised(T0 + 1, INFO, "a"), raised(T0, CRITICAL, "z"))).isPositive();
    }

    @Test
    @DisplayName("G-01 rule 2: equal timestamps, RAISED is later than CLEARED, whatever the severity and eventId")
    void g01_equalTimestamps_raisedIsLaterThanCleared() {
        assertThat(ORDER.compare(raised(T0, INFO, "a"), cleared(T0, CRITICAL, "z"))).isPositive();
        assertThat(ORDER.compare(cleared(T0, CRITICAL, "z"), raised(T0, INFO, "a"))).isNegative();
    }

    @Test
    @DisplayName("G-01 rule 3: equal timestamps, both RAISED, the higher severity is later: CRITICAL > MAJOR > WARNING > INFO")
    void g01_equalTimestampsBothRaised_higherSeverityIsLater() {
        // The eventIds go against the severities, so rule 4 would give the opposite answer.
        assertThat(ORDER.compare(raised(T0, CRITICAL, "a"), raised(T0, MAJOR, "z"))).isPositive();
        assertThat(ORDER.compare(raised(T0, MAJOR, "a"), raised(T0, WARNING, "z"))).isPositive();
        assertThat(ORDER.compare(raised(T0, WARNING, "a"), raised(T0, INFO, "z"))).isPositive();
        assertThat(ORDER.compare(raised(T0, INFO, "z"), raised(T0, WARNING, "a"))).isNegative();
    }

    // ---- G-02 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("G-01 rule 4: all else equal, the greater eventId as a string is later")
    void g01_allElseEqual_greaterEventIdIsLater() {
        assertThat(ORDER.compare(raised(T0, MAJOR, "e-b"), raised(T0, MAJOR, "e-a"))).isPositive();
        assertThat(ORDER.compare(raised(T0, MAJOR, "e-a"), raised(T0, MAJOR, "e-b"))).isNegative();
        // Rule 3 is only for two RAISED: between two CLEARED at the same time the severity is not looked at.
        assertThat(ORDER.compare(cleared(T0, INFO, "e-b"), cleared(T0, CRITICAL, "e-a"))).isPositive();
    }

    @Test
    @DisplayName("G-02 antisymmetry and consistency: compare(a, b) = -compare(b, a); 0 only for the same event")
    void g02_antisymmetricAndConsistent() {
        List<AlarmEvent> events = randomEvents(2_001L);
        for (AlarmEvent a : events) {
            for (AlarmEvent b : events) {
                int ab = Integer.signum(ORDER.compare(a, b));
                int ba = Integer.signum(ORDER.compare(b, a));
                assertThat(ab).as("signum compare(%s, %s) = -signum compare(%s, %s)", a, b, b, a).isEqualTo(-ba);
                assertThat(ab == 0).as("compare(%s, %s) is 0 only for the same event", a, b).isEqualTo(a == b);
            }
        }
    }

    @Test
    @DisplayName("G-02 transitivity: a < b and b < c give a < c")
    void g02_transitive() {
        List<AlarmEvent> events = randomEvents(2_002L);
        for (AlarmEvent a : events) {
            for (AlarmEvent b : events) {
                if (ORDER.compare(a, b) >= 0) {
                    continue;
                }
                for (AlarmEvent c : events) {
                    if (ORDER.compare(b, c) < 0) {
                        assertThat(ORDER.compare(a, c)).as("%s < %s < %s", a, b, c).isNegative();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("G-02 sorting a set gives the same order whatever the starting order")
    void g02_sortingDoesNotDependOnStartingOrder() {
        List<AlarmEvent> events = randomEvents(2_003L);
        List<AlarmEvent> reference = new ArrayList<>(events);
        reference.sort(ORDER);
        Random random = new Random(2_004L);
        for (int round = 0; round < 50; round++) {
            List<AlarmEvent> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, random);
            shuffled.sort(ORDER);
            assertThat(shuffled).as("round %d", round).isEqualTo(reference);
        }
    }

    // ---- G-03 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("G-03 eventId is compared as a string, not as a number: \"e-10\" < \"e-9\"")
    void g03_eventIdComparedAsString() {
        assertThat(ORDER.compare(raised(T0, MAJOR, "e-10"), raised(T0, MAJOR, "e-9"))).isNegative();
        assertThat(ORDER.compare(cleared(T0, MAJOR, "e-10"), cleared(T0, MAJOR, "e-9"))).isNegative();
    }
}
