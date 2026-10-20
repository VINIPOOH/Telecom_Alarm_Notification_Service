package com.se.telecomalarm.api;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The D tests of GET /api/alarms/statistics: D-06, D-07, D-08.
 * <p>
 * The body is exactly an object {@code {"CRITICAL": n, "MAJOR": n, "WARNING": n, "INFO": n}}: four keys, always all
 * four, zeros included. It counts active pairs, not events.
 */
class AlarmStatisticsApiTests extends AlarmStateTestBase {

    private static final String STATISTICS = "/api/alarms/statistics";

    /**
     * The body must be a JSON object (not an array of {severity, count}) with exactly the four keys, each an integer
     * number, and the counts must be the expected ones.
     */
    @SuppressWarnings("unchecked")
    private static void assertCounts(ApiResponse response, long critical, long major, long warning, long info) {
        Object body = response.json().read("$");
        assertThat(body).as("the body must be a JSON object, not an array: %s", response).isInstanceOf(Map.class);
        Map<String, Object> object = (Map<String, Object>) body;
        assertThat(object.keySet()).as("keys of the body (exactly the four severities): %s", response)
                .containsExactlyInAnyOrder("CRITICAL", "MAJOR", "WARNING", "INFO");
        object.forEach((severity, count) -> assertThat(count)
                .as("the count of %s must be a JSON integer: %s", severity, response)
                .isInstanceOfAny(Integer.class, Long.class));

        Map<String, Long> expected = Map.of("CRITICAL", critical, "MAJOR", major, "WARNING", warning, "INFO", info);
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            assertThat(((Number) object.get(entry.getKey())).longValue())
                    .as("count of %s in %s", entry.getKey(), response)
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("D-06 /statistics: an object with exactly the four severities, the zeros included")
    void counts_areByActivePairs_withAllFourSeveritiesAndZeros() {
        postAccepted(
                raised(at(0), "CRITICAL", "e1"),
                raised(at(1), "CRITICAL", "e2").forPair("BTS-100", "HIGH_CPU"),
                raised(at(2), "INFO", "e3").forPair("BTS-200", "CELL_DOWN"));

        assertCounts(statistics(), 2, 0, 0, 1);
    }

    @Test
    @DisplayName("D-07 /statistics on an empty database: all four severities are 0")
    void emptyDatabase_returnsFourZeros() {
        assertCounts(statistics(), 0, 0, 0, 0);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-08 /statistics after a severity change and a clearing: pairs are counted, not events")
    void severityChangeAndClearing_countPairsNotEvents() {
        // Pair A (BTS-100, CELL_DOWN): MAJOR, then CRITICAL - one pair, counted once, as CRITICAL (MAJOR stays 0).
        // Pair B (BTS-100, HIGH_CPU): WARNING, then cleared - not active, counted nowhere (WARNING stays 0).
        // The title of D-08 says "after clearing and a change of severity", its action lists only the change:
        // both are covered.
        postAccepted(
                raised(at(0), "MAJOR", "e1"),
                raised(at(1), "WARNING", "e2").forPair("BTS-100", "HIGH_CPU"),
                cleared(at(2), "e3").forPair("BTS-100", "HIGH_CPU"),
                raised(at(5), "CRITICAL", "e4"));

        assertCounts(statistics(), 1, 0, 0, 0);
    }

    /**
     * GET /statistics: 200 and a JSON content type are checked here, the body in {@link #assertCounts}.
     */
    private ApiResponse statistics() {
        ApiResponse response = get(STATISTICS);
        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        return response;
    }
}
