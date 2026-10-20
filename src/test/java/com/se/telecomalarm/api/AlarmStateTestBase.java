package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base of the section C tests (state logic): the notation of the test cases and the three ways the tests read state.
 * <ul>
 *   <li><b>Active state</b> - only through GET /api/alarms/active ({@link #activeAlarms()}, {@link #activeAlarmOf}),
 *       never by SQL on {@code active_alarms}: the tests check what a client sees, not how the active state is
 *       stored. This is the one place that reads active state; if the way of reading changes, only these two
 *       methods change.</li>
 *   <li><b>Result of each event</b> (ACCEPTED / REJECTED, number of rows) - by SQL on {@code alarm_events}
 *       ({@link #storedResults()}): the API does not show rejected events, so the table is the only place to see
 *       them.</li>
 *   <li><b>Visible history</b> - through GET /api/alarms/history ({@link #visibleHistory()}).</li>
 * </ul>
 * Notation: {@code raised(at(0), "MAJOR", "e1")} is {@code R(10:00, MAJOR, e1)} of the test cases,
 * {@code cleared(at(5), "e2")} is {@code C(10:05, e2)}; the pair is (BTS-100, CELL_DOWN) unless given.
 * <p>
 * O5 (no notification for a rejected event) is checked in {@link #postRejected}. The notification parts of C-06,
 * C-10, C-20 and C-23 are tests of section E ({@code NotificationTests}).
 */
public abstract class AlarmStateTestBase extends ApiTestBase {

    protected static final long MINUTE = 60_000L;
    protected static final long SECOND = 1_000L;

    protected static final String ACCEPTED = ExpectedAlarmState.ACCEPTED;
    protected static final String REJECTED = ExpectedAlarmState.REJECTED;

    /**
     * The fields of one record of /active and /elements/{id}.
     */
    private static final Set<String> ACTIVE_RECORD_FIELDS =
            Set.of("elementId", "alarmCode", "severity", "description", "eventId", "timestamp");

    /**
     * The fields of one item of /history.
     */
    private static final Set<String> HISTORY_ITEM_FIELDS =
            Set.of("eventId", "elementId", "alarmCode", "severity", "status", "timestamp", "description");

    // ---- notation ---------------------------------------------------------------------------------------------------

    /**
     * {@code R(timestamp, severity, eventId)} for the default pair.
     */
    protected static TestEvent raised(long timestamp, String severity, String eventId) {
        return TestEvent.raised(timestamp, severity, eventId);
    }

    /**
     * {@code C(timestamp, eventId)} for the default pair.
     */
    protected static TestEvent cleared(long timestamp, String eventId) {
        return TestEvent.cleared(timestamp, eventId);
    }

    /**
     * The time {@code 10:MM} of the test cases: {@code at(5)} is 10:05, {@code at(-10)} is 09:50.
     */
    protected long at(int minutes) {
        return t0 + minutes * MINUTE;
    }

    // ---- sending ----------------------------------------------------------------------------------------------------

    protected ApiResponse post(TestEvent event) {
        return postEvent(event.json());
    }

    /**
     * Sends the events one after another; each must be answered 201.
     */
    protected void postAccepted(TestEvent... events) {
        for (TestEvent event : events) {
            ApiResponse response = post(event);
            assertThat(response.status()).as("HTTP status of %s: %s", event, response).isEqualTo(201);
        }
    }

    /**
     * Sends the event and expects 409 with a valid problem body (O1-O3) and no notification (O5, the same rule as in
     * {@link #postRawAndExpectProblem}: the queue is drained before counting, so notifications of earlier accepted
     * events are already counted). O4 does not apply: a rejected CLEARED is written to the history with acceptance
     * REJECTED (rule B5).
     */
    protected ApiResponse postRejected(TestEvent event) {
        awaitNotificationQueueEmpty();
        int notificationsBefore = receivedNotifications().size();

        ApiResponse response = post(event);

        assertThat(response.status()).as("HTTP status of %s: %s", event, response).isEqualTo(409);
        ProblemAssertions.assertProblem(response, 409);
        assertNoNotificationSince(notificationsBefore);
        return response;
    }

    // ---- active state: through the API only -------------------------------------------------------------------------

    /**
     * GET /api/alarms/active as records, in the order the service returned them.
     */
    protected List<ActiveAlarm> activeAlarms() {
        ApiResponse response = get("/api/alarms/active");
        assertStatus(response, 200);
        Object body = response.json().read("$");
        assertThat(body).as("GET /api/alarms/active must return a JSON array: %s", response).isInstanceOf(List.class);
        List<ActiveAlarm> records = new ArrayList<>();
        for (Object element : (List<?>) body) {
            Map<String, Object> record = jsonObject(element, "a record of /active", response);
            assertThat(record.keySet()).as("fields of a record of /active: %s", response)
                    .containsExactlyInAnyOrderElementsOf(ACTIVE_RECORD_FIELDS);
            records.add(new ActiveAlarm(
                    (String) record.get("elementId"),
                    (String) record.get("alarmCode"),
                    (String) record.get("severity"),
                    (String) record.get("description"),
                    (String) record.get("eventId"),
                    asLong(record.get("timestamp"), response)));
        }
        return records;
    }

    /**
     * The active record of one pair; fails if /active lists the pair more than once.
     */
    protected Optional<ActiveAlarm> activeAlarmOf(String elementId, String alarmCode) {
        List<ActiveAlarm> all = activeAlarms();
        List<ActiveAlarm> ofPair = all.stream()
                .filter(alarm -> alarm.elementId().equals(elementId) && alarm.alarmCode().equals(alarmCode))
                .toList();
        assertThat(ofPair).as("records of (%s, %s) in /active (at most one per pair): %s", elementId, alarmCode, all)
                .hasSizeLessThanOrEqualTo(1);
        return ofPair.stream().findFirst();
    }

    // ---- result of each event: SQL on the history -------------------------------------------------------------------

    /**
     * eventId -> acceptance (ACCEPTED / REJECTED) of every row of alarm_events, sorted by eventId.
     */
    protected Map<String, String> storedResults() {
        Map<String, String> results = new TreeMap<>();
        for (AlarmDb.HistoryRow row : db.history()) {
            results.put(row.eventId(), row.acceptance());
        }
        return results;
    }

    // ---- visible history: through the API ---------------------------------------------------------------------------

    /**
     * The items of GET /api/alarms/history without filters, in the order the service returned them; numbers are
     * normalised to {@link Long}. The tests of section C store far fewer events than the default page size (50),
     * so the whole history is one page: a non-null {@code next} is reported as a failure, not followed.
     */
    protected List<Map<String, Object>> visibleHistory() {
        ApiResponse response = get("/api/alarms/history");
        assertStatus(response, 200);
        Map<String, Object> body = jsonObject(response.json().read("$"), "the body of /history", response);
        assertThat(body.keySet()).as("fields of the /history body: %s", response).containsExactlyInAnyOrder("items", "next");
        assertThat(body.get("next")).as("next of /history: these tests fit in one page: %s", response).isNull();
        Object items = body.get("items");
        assertThat(items).as("items of /history must be an array: %s", response).isInstanceOf(List.class);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : (List<?>) items) {
            Map<String, Object> item = new LinkedHashMap<>(jsonObject(element, "an item of /history", response));
            assertThat(item.keySet()).as("fields of an item of /history: %s", response)
                    .containsExactlyInAnyOrderElementsOf(HISTORY_ITEM_FIELDS);
            item.put("timestamp", asLong(item.get("timestamp"), response));
            result.add(item);
        }
        return result;
    }

    /**
     * The eventIds of {@link #visibleHistory()}, in its order.
     */
    protected List<String> visibleHistoryEventIds() {
        return visibleHistory().stream().map(item -> (String) item.get("eventId")).toList();
    }

    // ---- JSON helpers -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObject(Object value, String what, ApiResponse response) {
        assertThat(value).as("%s must be a JSON object: %s", what, response).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /**
     * JSON numbers come back as Integer or Long depending on their size; the tests compare longs.
     */
    private static long asLong(Object value, ApiResponse response) {
        assertThat(value).as("timestamp must be a JSON number: %s", response).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}
