package com.se.telecomalarm.acceptance;

import java.util.List;
import com.se.telecomalarm.api.ActiveAlarm;
import com.se.telecomalarm.api.AlarmDb.HistoryRow;
import com.se.telecomalarm.api.AlarmStateTestBase;
import com.se.telecomalarm.api.ApiResponse;
import com.se.telecomalarm.api.TestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance checks of the assignment (section 7) for intake, active state and history: one test per check,
 * written as given / when / then.
 * <p>
 * These tests check exactly what the assignment asks and nothing more. The detailed cases behind each check (every
 * field of the answers, parallel duplicates, events out of order) are in the tests of the {@code api} package.
 * <p>
 * State is read the way a client and an operator would see it: the active alarms through
 * {@code GET /api/alarms/active}, the history directly in the {@code alarm_events} table.
 */
@DisplayName("Assignment, section 7: intake, active state and history")
class AlarmIngestionAcceptanceTests extends AlarmStateTestBase {

    private static final String ELEMENT = "BTS-102";
    private static final String ALARM_CODE = "CELL_DOWN";

    /**
     * An event of the alarm (BTS-102, CELL_DOWN), the example of the assignment.
     */
    private static TestEvent event(String eventId, String status, long timestamp) {
        return new TestEvent(eventId, ELEMENT, ALARM_CODE, "CRITICAL", status, timestamp, "Cell is not responding");
    }

    /**
     * The row this event must leave in {@code alarm_events}. {@code notification_sent} is set later by the sender,
     * so the comparisons above ignore it.
     */
    private static HistoryRow storedAsAccepted(TestEvent event) {
        return new HistoryRow(event.eventId(), event.elementId(), event.alarmCode(), event.severity(), event.status(),
                              event.timestamp(), event.description(), ACCEPTED, false);
    }

    @Test
    @DisplayName("7.1 a valid event stores one history record and updates the active alarm state")
    void givenNoAlarms_whenValidEventIsPosted_thenOneHistoryRecordIsStoredAndTheAlarmIsActive() {
        // given
        TestEvent raised = event("EVT-10001", "RAISED", at(0));

        // when
        ApiResponse response = post(raised);

        // then
        assertStatus(response, 201);
        assertThat(storedHistory())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("notificationSent")
                .containsExactly(storedAsAccepted(raised));
        assertThat(activeAlarms()).containsExactly(ActiveAlarm.of(raised));
    }

    @Test
    @DisplayName("7.2 RAISED followed by CLEARED removes the alarm from the active state and leaves two history records")
    void givenRaisedAlarm_whenItIsCleared_thenItIsNotActiveAndTheHistoryHasBothEvents() {
        // given
        TestEvent raised = event("EVT-10001", "RAISED", at(0));
        postAccepted(raised);

        // when
        TestEvent cleared = event("EVT-10002", "CLEARED", at(5));
        ApiResponse response = post(cleared);

        // then
        assertStatus(response, 201);
        assertThat(activeAlarms()).isEmpty();
        assertThat(storedHistory())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("notificationSent")
                .containsExactly(storedAsAccepted(raised), storedAsAccepted(cleared));
    }

    @Test
    @DisplayName("7.3 a duplicate eventId does not create a second history record (and no second notification)")
    void givenAcceptedEvent_whenTheSameEventIsPostedAgain_thenNoSecondHistoryRecordAndNoSecondNotification() {
        // given
        TestEvent raised = event("EVT-10001", "RAISED", at(0));
        postAccepted(raised);
        awaitNotificationOf(raised.eventId());

        // when
        ApiResponse response = post(raised);

        // then
        assertStatus(response, 200);
        assertThat(response.jsonObject()).containsEntry("notification", "NOT_SENT_DUPLICATE");
        assertThat(storedHistory())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("notificationSent")
                .containsExactly(storedAsAccepted(raised));
        awaitNotificationQueueEmpty();
        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly(raised.eventId());
    }

    /**
     * Every row of {@code alarm_events}, sorted by eventId.
     */
    private List<HistoryRow> storedHistory() {
        return db.history();
    }
}
