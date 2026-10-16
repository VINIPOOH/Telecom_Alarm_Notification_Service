package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section D of the test-case spec, GET /api/alarms/elements/{elementId} (SPEC section 4): D-03, D-04, D-05.
 * <p>
 * The record of /elements is the same record as in /active (one serialisation, so a client reads both responses with
 * one parser): six keys, {@code description: null} present, timestamp a JSON number. That is why these tests read the
 * raw response, like {@link ActiveAlarmsApiTests}. The two parsing helpers below repeat the private ones of that class,
 * which is not to be changed.
 */
class ElementAlarmsApiTests extends AlarmStateTestBase {

    private static final String ELEMENTS = "/api/alarms/elements/";

    /**
     * The body as a list of records, each a map of exactly the keys that are in the JSON; the body must be a bare
     * array and the timestamp of every record a JSON number (normalised to {@link Long}).
     */
    private static List<Map<String, Object>> records(ApiResponse response) {
        Object body = response.json().read("$");
        assertThat(body).as("the body must be a bare JSON array: %s", response).isInstanceOf(List.class);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object element : (List<?>) body) {
            assertThat(element).as("an element of the array must be a JSON object: %s", response).isInstanceOf(Map.class);
            Map<String, Object> record = new LinkedHashMap<>();
            ((Map<?, ?>) element).forEach((key, value) -> record.put((String) key, value));
            assertThat(record.get("timestamp")).as("timestamp must be a JSON number: %s", response).isInstanceOf(Number.class);
            record.put("timestamp", ((Number) record.get("timestamp")).longValue());
            records.add(record);
        }
        return records;
    }

    /**
     * What one record must look like: exactly these six keys, {@code description} present even if null.
     */
    private static Map<String, Object> expectedRecord(ActiveAlarm alarm) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("elementId", alarm.elementId());
        record.put("alarmCode", alarm.alarmCode());
        record.put("severity", alarm.severity());
        record.put("description", alarm.description());
        record.put("eventId", alarm.eventId());
        record.put("timestamp", alarm.timestamp());
        return record;
    }

    @Test
    @DisplayName("D-03 /elements/{id}: only the active alarms of this element, in the record of /active, sorted by alarmCode")
    void elementAlarms_areOnlyThoseOfThatElement_inTheRecordOfActive_sortedByAlarmCode() {
        // The arrival order is not the expected order (alarmCode), so a response in insertion order fails.
        // BTS-200 has an alarm with the same alarmCode as one of BTS-100, so a filter by alarmCode alone fails too.
        // highCpu has no description: the key must be present with the value null, as in /active.
        TestEvent powerFailure = raised(at(0), "CRITICAL", "e1").forPair("BTS-100", "POWER_FAILURE").withDescription("Mains lost");
        TestEvent cellDown = raised(at(1), "MAJOR", "e2");
        TestEvent highCpu = raised(at(2), "WARNING", "e3").forPair("BTS-100", "HIGH_CPU").withDescription(null);
        TestEvent otherElement = raised(at(3), "CRITICAL", "e4").forPair("BTS-200", "CELL_DOWN");
        postAccepted(powerFailure, cellDown, highCpu, otherElement);

        ApiResponse response = get(ELEMENTS + "BTS-100");

        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(records(response)).as("records of /elements/BTS-100: %s", response).containsExactly(
                expectedRecord(ActiveAlarm.of(cellDown)),
                expectedRecord(ActiveAlarm.of(highCpu)),
                expectedRecord(ActiveAlarm.of(powerFailure)));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-04 /elements/{id} of an unknown element: 200 and an empty array, not 404")
    void unknownElement_returnsEmptyArray_notNotFound() {
        // Another element is active, so the empty answer is not just an empty database.
        postAccepted(raised(at(0), "MAJOR", "e1"));

        ApiResponse response = get(ELEMENTS + "BTS-999");

        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(response.body().strip()).as("body of %s", response).isEqualTo("[]");
    }

    @Test
    @DisplayName("D-05 /elements/{id} of an element whose alarms are all cleared: 200 and an empty array")
    void elementWithAllAlarmsCleared_returnsEmptyArray() {
        // BTS-100 had two alarms and both are cleared; BTS-200 is still active, so the history and /active are not empty.
        postAccepted(
                raised(at(0), "MAJOR", "e1"),
                raised(at(1), "WARNING", "e2").forPair("BTS-100", "HIGH_CPU"),
                cleared(at(2), "e3"),
                cleared(at(3), "e4").forPair("BTS-100", "HIGH_CPU"),
                raised(at(4), "CRITICAL", "e5").forPair("BTS-200", "CELL_DOWN"));

        ApiResponse response = get(ELEMENTS + "BTS-100");

        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(response.body().strip()).as("body of %s", response).isEqualTo("[]");
    }
}
