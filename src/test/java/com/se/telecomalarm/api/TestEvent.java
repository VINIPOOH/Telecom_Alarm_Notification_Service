package com.se.telecomalarm.api;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.se.telecomalarm.api.AlarmEventJson.ALARM_CODE;
import static com.se.telecomalarm.api.AlarmEventJson.DESCRIPTION;
import static com.se.telecomalarm.api.AlarmEventJson.ELEMENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.EVENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.SEVERITY;
import static com.se.telecomalarm.api.AlarmEventJson.STATUS;

/**
 * One alarm event as the state tests (section C) see it: typed fields, so the tests can both send it
 * ({@link #json()}) and compute what the service must do with it ({@link ExpectedAlarmState}).
 * <p>
 * {@link AlarmEventJson} stays the only place that writes the JSON; this record only fills it.
 * A null {@code description} means the field is left out of the JSON.
 */
public record TestEvent(
        String eventId,
        String elementId,
        String alarmCode,
        String severity,
        String status,
        long timestamp,
        String description) {

    static final String DEFAULT_ELEMENT = "BTS-100";
    static final String DEFAULT_ALARM_CODE = "CELL_DOWN";
    static final String DEFAULT_DESCRIPTION = "Cell is down";

    /**
     * Severity sent with every CLEARED. The field is mandatory in the request, but the total order looks at severity
     * only between two RAISED events (rule B2), so its value does not matter for the state.
     */
    static final String CLEARED_SEVERITY = "MAJOR";

    /**
     * {@code R(timestamp, severity, eventId)} of the default pair (BTS-100, CELL_DOWN).
     */
    static TestEvent raised(long timestamp, String severity, String eventId) {
        return new TestEvent(eventId, DEFAULT_ELEMENT, DEFAULT_ALARM_CODE, severity, "RAISED", timestamp, DEFAULT_DESCRIPTION);
    }

    /**
     * {@code C(timestamp, eventId)} of the default pair (BTS-100, CELL_DOWN).
     */
    static TestEvent cleared(long timestamp, String eventId) {
        return new TestEvent(eventId, DEFAULT_ELEMENT, DEFAULT_ALARM_CODE, CLEARED_SEVERITY, "CLEARED", timestamp, DEFAULT_DESCRIPTION);
    }

    /**
     * The same event for another pair.
     */
    public TestEvent forPair(String elementId, String alarmCode) {
        return new TestEvent(eventId, elementId, alarmCode, severity, status, timestamp, description);
    }

    /**
     * The same event with another description; null leaves the field out of the JSON.
     */
    TestEvent withDescription(String description) {
        return new TestEvent(eventId, elementId, alarmCode, severity, status, timestamp, description);
    }

    boolean isRaised() {
        return "RAISED".equals(status);
    }

    /**
     * "elementId|alarmCode": the alarm (pair) this event belongs to.
     */
    String pairKey() {
        return elementId + "|" + alarmCode;
    }

    /**
     * The request body for POST /api/alarms.
     */
    AlarmEventJson json() {
        AlarmEventJson json = AlarmEventJson.valid(timestamp)
                .with(EVENT_ID, eventId)
                .with(ELEMENT_ID, elementId)
                .with(ALARM_CODE, alarmCode)
                .with(SEVERITY, severity)
                .with(STATUS, status);
        return description == null ? json.without(DESCRIPTION) : json.with(DESCRIPTION, description);
    }

    /**
     * This event as an item of GET /api/alarms/history: the full event with {@code status},
     * {@code description: null} when it has none. The timestamp is a {@link Long}, as the tests normalise numbers.
     */
    Map<String, Object> asHistoryItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", eventId);
        item.put("elementId", elementId);
        item.put("alarmCode", alarmCode);
        item.put("severity", severity);
        item.put("status", status);
        item.put("timestamp", timestamp);
        item.put("description", description);
        return item;
    }

    /**
     * Short form for failure messages, in the notation of the test cases: {@code R(1791800000000, MAJOR, e1)}.
     */
    String label() {
        String pair = DEFAULT_ELEMENT.equals(elementId) && DEFAULT_ALARM_CODE.equals(alarmCode)
                      ? "" : " " + elementId + "/" + alarmCode;
        return isRaised()
               ? "R(" + timestamp + ", " + severity + ", " + eventId + ")" + pair
               : "C(" + timestamp + ", " + eventId + ")" + pair;
    }

    @Override
    public String toString() {
        return label();
    }
}
