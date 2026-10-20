package com.se.telecomalarm.api;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C tests: a CLEARED that arrives before its RAISED, and its resurrection (rule B5: an accepted RAISED turns to
 * ACCEPTED every REJECTED CLEARED of its pair that is later than it).
 * <p>
 * The notification parts of C-20 (e1, then e2) and C-23 (e1, e2, e3) are E-11 in {@code NotificationTests}.
 */
class AlarmResurrectionTests extends AlarmStateTestBase {

    @Test
    @DisplayName("C-20 a CLEARED rejected first is resurrected by an earlier RAISED that arrives later")
    void clearedRejectedFirst_isResurrectedByEarlierRaisedArrivingLater() {
        postRejected(cleared(at(5), "e2"));

        postAccepted(raised(at(0), "MAJOR", "e1"));

        assertThat(activeAlarms()).as("/active").isEmpty();
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
        assertThat(visibleHistoryEventIds()).as("/history").containsExactly("e1", "e2");
    }

    @Test
    @DisplayName("C-21 a repeat of a resurrected CLEARED is an exact repeat of an accepted event: 200")
    void repeatOfResurrectedCleared_is200() {
        TestEvent cleared = cleared(at(5), "e2");
        postRejected(cleared);
        postAccepted(raised(at(0), "MAJOR", "e1"));

        ApiResponse repeat = post(cleared);

        assertStatus(repeat, 200);
        assertThat(repeat.mediaType()).as("Content-Type of %s", repeat).isEqualTo("application/json");
        assertThat(repeat.jsonObject()).as("body of %s", repeat)
                .isEqualTo(Map.of("eventId", "e2", "notification", "NOT_SENT_DUPLICATE"));
        assertThat(storedResults()).as("no new row, results unchanged").isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED));
        assertThat(activeAlarms()).as("/active").isEmpty();
    }

    @Test
    @DisplayName("C-22 a CLEARED earlier than the RAISED is not resurrected and its repeat stays a 409")
    void clearedEarlierThanRaised_isNotResurrected_repeatStays409() {
        TestEvent early = cleared(at(-10), "e2");
        TestEvent raised = raised(at(0), "MAJOR", "e1");
        postRejected(early);

        postAccepted(raised);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(raised));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
        assertThat(visibleHistoryEventIds()).as("/history").containsExactly("e1");

        postRejected(early);

        assertThat(storedResults()).as("the repeat changes nothing").isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED));
        assertThat(activeAlarms()).as("the repeat changes nothing").containsExactly(ActiveAlarm.of(raised));
    }

    @Test
    @DisplayName("C-23 one RAISED resurrects every rejected CLEARED of its pair that is later than it")
    void oneRaised_resurrectsEveryLaterRejectedCleared() {
        postRejected(cleared(at(5), "e2"));
        postRejected(cleared(at(10), "e3"));

        postAccepted(raised(at(0), "MAJOR", "e1"));

        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", ACCEPTED, "e3", ACCEPTED));
        assertThat(activeAlarms()).as("/active").isEmpty();
    }

    @Test
    @DisplayName("C-24 a RAISED of another pair does not resurrect a rejected CLEARED")
    void raisedOfAnotherPair_doesNotResurrect() {
        TestEvent clearedCellDown = cleared(at(5), "e1");
        TestEvent raisedHighCpu = raised(at(0), "MAJOR", "e2").forPair("BTS-100", "HIGH_CPU");
        postRejected(clearedCellDown);

        postAccepted(raisedHighCpu);

        assertThat(storedResults()).isEqualTo(Map.of("e1", REJECTED, "e2", ACCEPTED));
        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(raisedHighCpu));
    }

    @Test
    @DisplayName("C-25 rejected events take no part in rule 2: a CLEARED earlier than every RAISED stays rejected")
    void rejectedEventsTakeNoPartInRule2() {
        TestEvent latest = raised(at(10), "CRITICAL", "e3");
        postAccepted(raised(at(0), "MAJOR", "e1"));
        postRejected(cleared(at(-60), "e2")); // 09:00: no RAISED is earlier than it

        postAccepted(latest);

        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(latest));
        assertThat(storedResults()).isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED, "e3", ACCEPTED));
        assertThat(visibleHistoryEventIds()).as("/history").isEqualTo(List.of("e1", "e3"));
    }
}
