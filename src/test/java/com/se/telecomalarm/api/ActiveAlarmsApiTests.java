package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The D tests of GET /api/alarms/active: D-01, D-01a, D-02.
 * <p>
 * These tests read the raw response, not {@link #activeAlarms()}: the helper of the state tests checks the fields
 * and parses the records, here the shape of the response itself is under test (a bare array, exactly six keys,
 * {@code description: null} present, timestamp a JSON number, the order of the records).
 */
class ActiveAlarmsApiTests extends AlarmStateTestBase {

    private static final String ACTIVE = "/api/alarms/active";

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
     * What one record of /active must look like: exactly these six keys, {@code description} present even if null.
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
    @DisplayName("D-01 /active: a bare array of the last accepted event of every active pair, six fields, sorted, cleared pair absent")
    void activePairs_areListedAsLastAcceptedEvent_withSixFields_sortedByElementId() {
        // Arrival order is the reverse of the expected order, so a response in insertion order fails.
        TestEvent highCpu = raised(at(0), "CRITICAL", "e1").forPair("BTS-200", "HIGH_CPU").withDescription("CPU is at 95%");
        TestEvent cellDownFirst = raised(at(1), "WARNING", "e2");
        TestEvent cellDownLast = raised(at(3), "MAJOR", "e5").withDescription("Cell is degraded");
        TestEvent powerFailure = raised(at(2), "MAJOR", "e3").forPair("BTS-300", "POWER_FAILURE");
        TestEvent powerFailureCleared = cleared(at(4), "e4").forPair("BTS-300", "POWER_FAILURE");
        postAccepted(highCpu, cellDownFirst, powerFailure, cellDownLast, powerFailureCleared);

        ApiResponse response = get(ACTIVE);

        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(records(response)).as("records of /active: %s", response).containsExactly(
                expectedRecord(ActiveAlarm.of(cellDownLast)),
                expectedRecord(ActiveAlarm.of(highCpu)));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-01a /active: a pair whose last event has no description has the key description with the value null")
    void lastEventWithoutDescription_isListedWithDescriptionNull() {
        TestEvent withDescription = raised(at(0), "MAJOR", "e1").withDescription("a");
        TestEvent withoutDescription = raised(at(5), "MAJOR", "e2").withDescription(null);
        postAccepted(withDescription, withoutDescription);

        ApiResponse response = get(ACTIVE);

        assertStatus(response, 200);
        List<Map<String, Object>> records = records(response);
        assertThat(records).as("records of /active: %s", response).hasSize(1);
        assertThat(records.get(0)).as("the record: %s", response)
                .containsKey("description")
                .containsEntry("description", null)
                .containsEntry("eventId", "e2");
    }

    @Test
    @DisplayName("D-02 /active on an empty database: 200 and an empty array")
    void emptyDatabase_returnsEmptyArray() {
        ApiResponse response = get(ACTIVE);

        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(response.body().strip()).as("body of %s", response).isEqualTo("[]");
    }
}
