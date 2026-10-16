package com.se.telecomalarm.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.se.telecomalarm.api.AlarmDb.HistoryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.se.telecomalarm.api.AlarmEventJson.ALARM_CODE;
import static com.se.telecomalarm.api.AlarmEventJson.DESCRIPTION;
import static com.se.telecomalarm.api.AlarmEventJson.ELEMENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.EVENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.SEVERITY;
import static com.se.telecomalarm.api.AlarmEventJson.STATUS;
import static com.se.telecomalarm.api.AlarmEventJson.TIMESTAMP;
import static com.se.telecomalarm.api.ProblemAssertions.errorFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Section A of the test-case spec: acceptance and validation of POST /api/alarms.
 */
class AlarmIngestionValidationTests extends ApiTestBase {

    private static final long MINUTE = 60_000L;

    // ---- A-01 .. A-05: required fields ----------------------------------------------------------------------

    static Stream<String> requiredFields() {
        return Stream.of(EVENT_ID, ELEMENT_ID, ALARM_CODE, SEVERITY, STATUS, TIMESTAMP);
    }

    static Stream<Arguments> blankStrings() {
        return Stream.of(EVENT_ID, ELEMENT_ID, ALARM_CODE)
                .flatMap(field -> Stream.of("", "   ").map(value -> arguments(field, value)));
    }

    static Stream<Arguments> enumsAsNumbers() {
        return Stream.of(arguments(SEVERITY, 2), arguments(STATUS, 0));
    }

    static Stream<Arguments> tooFarInTheFuture() {
        return Stream.of(
                arguments("+5 min 1 s", 5 * MINUTE + 1_000L),
                arguments("+10 min", 10 * MINUTE));
    }

    static Stream<Arguments> withinTolerance() {
        return Stream.of(
                arguments("+4 min 59 s", 5 * MINUTE - 1_000L),
                arguments("+1 min", MINUTE));
    }

    static Stream<Arguments> limitsAtTheBoundary() {
        return Stream.of(
                arguments(EVENT_ID, 64),
                arguments(ELEMENT_ID, 64),
                arguments(ALARM_CODE, 64),
                arguments(DESCRIPTION, 1024));
    }

    static Stream<Arguments> limitsOverTheBoundary() {
        return Stream.of(
                arguments(EVENT_ID, 65),
                arguments(ELEMENT_ID, 65),
                arguments(ALARM_CODE, 65),
                arguments(DESCRIPTION, 1025));
    }

    // ---- A-06 .. A-09: severity and status values -----------------------------------------------------------

    private static String storedValue(HistoryRow row, String field) {
        return switch (field) {
            case EVENT_ID -> row.eventId();
            case ELEMENT_ID -> row.elementId();
            case ALARM_CODE -> row.alarmCode();
            case DESCRIPTION -> row.description();
            default -> throw new IllegalArgumentException("No such text column: " + field);
        };
    }

    @Test
    @DisplayName("A-01 valid RAISED is accepted")
    void a01_validRaised_isAccepted() {
        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with(EVENT_ID, "e1").with(SEVERITY, "CRITICAL"));

        assertStatus(response, 201);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(response.jsonObject()).isEqualTo(Map.of("eventId", "e1", "notification", "QUEUED"));

        assertThat(onlyHistoryRow())
                .usingRecursiveComparison()
                .ignoringFields("notificationSent")
                .isEqualTo(new HistoryRow(
                        "e1", "BTS-100", "CELL_DOWN", "CRITICAL", "RAISED", t0, "Cell is down", "ACCEPTED", false));

        ApiResponse active = get("/api/alarms/active");
        assertStatus(active, 200);
        List<Object> matches = active.json().read("$[?(@.elementId == 'BTS-100' && @.alarmCode == 'CELL_DOWN')]");
        assertThat(matches).as("the pair in /active: %s", active).hasSize(1);
    }

    @ParameterizedTest(name = "without {0}")
    @MethodSource("requiredFields")
    @DisplayName("A-02 missing mandatory field is a 400 naming the field")
    void a02_missingMandatoryField_isRejected(String field) {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).without(field), 400);

        assertThat(errorFields(response)).contains(field);
    }

    @ParameterizedTest(name = "{0} = null")
    @MethodSource("requiredFields")
    @DisplayName("A-03 mandatory field sent as null is a 400 naming the field")
    void a03_nullMandatoryField_isRejected(String field) {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).with(field, null), 400);

        assertThat(errorFields(response)).contains(field);
    }

    @ParameterizedTest(name = "explicit null: {0}")
    @ValueSource(booleans = { false, true })
    @DisplayName("A-04 description absent or null is accepted and stored as NULL")
    void a04_noDescription_isAccepted(boolean explicitNull) {
        AlarmEventJson base = AlarmEventJson.valid(t0);
        AlarmEventJson event = explicitNull ? base.with(DESCRIPTION, null) : base.without(DESCRIPTION);

        ApiResponse response = postEvent(event);

        assertStatus(response, 201);
        assertThat(onlyHistoryRow().description()).isNull();
    }

    // ---- A-10 .. A-15: body and Content-Type ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} = [{1}]")
    @MethodSource("blankStrings")
    @DisplayName("A-05 empty and blank strings are a 400 naming the field")
    void a05_blankString_isRejected(String field, String value) {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).with(field, value), 400);

        assertThat(errorFields(response)).contains(field);
    }

    @Test
    @DisplayName("A-06 severity outside the list is a 400 naming the field and the allowed values")
    void a06_unknownSeverity_isRejected() {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).with(SEVERITY, "HIGH"), 400);

        assertThat(errorFields(response)).contains("severity");
        assertThat(response.body()).contains("INFO", "WARNING", "MAJOR", "CRITICAL");
    }

    @Test
    @DisplayName("A-07 status outside the list is a 400 naming the field and the allowed values")
    void a07_unknownStatus_isRejected() {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).with(STATUS, "OPEN"), 400);

        assertThat(errorFields(response)).contains("status");
        assertThat(response.body()).contains("RAISED", "CLEARED");
    }

    @ParameterizedTest(name = "{0} = {1} (a number)")
    @MethodSource("enumsAsNumbers")
    @DisplayName("A-08 enum value sent as a number is a 400, not an ordinal")
    void a08_enumAsNumber_isRejected(String field, int number) {
        postAndExpectProblem(AlarmEventJson.valid(t0).with(field, number), 400);
    }

    @Test
    @DisplayName("A-09 enum value in another case is a 400")
    void a09_lowerCaseEnum_isRejected() {
        postAndExpectProblem(AlarmEventJson.valid(t0).with(SEVERITY, "critical"), 400);
    }

    @Test
    @DisplayName("A-10 broken JSON is a 400 without parser text")
    void a10_brokenJson_isRejected() {
        ApiResponse response = postRawAndExpectProblem("{\"eventId\": \"e1\",", JSON, 400);

        String detail = response.json().read("$.detail");
        assertThat(detail)
                .doesNotContainIgnoringCase("line:")
                .doesNotContainIgnoringCase("column:")
                .doesNotContainIgnoringCase("[Source:")
                .doesNotContainIgnoringCase("end-of-input")
                .doesNotContainIgnoringCase("expecting")
                .doesNotContainIgnoringCase("parse error")
                .doesNotContain("eventId");
    }

    // ---- A-16 .. A-23: timestamp ------------------------------------------------------------------------------

    @Test
    @DisplayName("A-11 empty body is a 400")
    void a11_emptyBody_isRejected() {
        postRawAndExpectProblem("", JSON, 400);
    }

    @ParameterizedTest(name = "body: {0}")
    @ValueSource(strings = { "[]", "\"text\"", "42" })
    @DisplayName("A-12 JSON that is not an object is a 400")
    void a12_notAnObject_isRejected(String body) {
        postRawAndExpectProblem(body, JSON, 400);
    }

    @Test
    @DisplayName("A-13 wrong Content-Type is a 415")
    void a13_wrongContentType_isRejected() {
        postRawAndExpectProblem(AlarmEventJson.valid(t0).toJson(), "text/plain", 415);
    }

    @Test
    @DisplayName("A-14 missing Content-Type is a 415")
    void a14_noContentType_isRejected() {
        postRawAndExpectProblem(AlarmEventJson.valid(t0).toJson(), null, 415);
    }

    @Test
    @DisplayName("A-15 Content-Type with a charset is accepted")
    void a15_contentTypeWithCharset_isAccepted() {
        ApiResponse response = postRaw(AlarmEventJson.valid(t0).toJson(), "application/json;charset=UTF-8");

        assertStatus(response, 201);
    }

    @ParameterizedTest(name = "timestamp = \"{0}\"")
    @ValueSource(strings = { "2026-10-20T10:00:00Z", "abc" })
    @DisplayName("A-16 timestamp that is not a number is a 400 naming the field")
    void a16_timestampNotANumber_isRejected(String value) {
        ApiResponse response = postAndExpectProblem(AlarmEventJson.valid(t0).with(TIMESTAMP, value), 400);

        assertThat(errorFields(response)).contains("timestamp");
    }

    @Test
    @DisplayName("A-17 negative timestamp is a 400")
    void a17_negativeTimestamp_isRejected() {
        postAndExpectProblem(AlarmEventJson.valid(t0).with(TIMESTAMP, -1L), 400);
    }

    // A-21 is not here: the exact tolerance boundary cannot be tested through HTTP, it is unit test G-04.

    @Test
    @DisplayName("A-18 timestamp 0 is accepted: there is no lower bound")
    void a18_zeroTimestamp_isAccepted() {
        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with(TIMESTAMP, 0L));

        assertStatus(response, 201);
        assertThat(onlyHistoryRow().timestamp()).isZero();
    }

    @ParameterizedTest(name = "now {0}")
    @MethodSource("tooFarInTheFuture")
    @DisplayName("A-19 timestamp beyond the 5-minute tolerance is a 400")
    void a19_tooFarInTheFuture_isRejected(String label, long offsetMillis) {
        long timestamp = System.currentTimeMillis() + offsetMillis;

        postAndExpectProblem(AlarmEventJson.valid(t0).with(TIMESTAMP, timestamp), 400);
    }

    // ---- A-24 .. A-27: unknown fields, lengths, spaces, several errors -----------------------------------------

    @ParameterizedTest(name = "now {0}")
    @MethodSource("withinTolerance")
    @DisplayName("A-20 timestamp within the 5-minute tolerance is accepted")
    void a20_withinTolerance_isAccepted(String label, long offsetMillis) {
        long timestamp = System.currentTimeMillis() + offsetMillis;

        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with(TIMESTAMP, timestamp));

        assertStatus(response, 201);
    }

    @Test
    @DisplayName("A-22 fractional timestamp is a 400")
    void a22_fractionalTimestamp_isRejected() {
        BigDecimal fractional = BigDecimal.valueOf(t0).add(new BigDecimal("0.5"));

        postAndExpectProblem(AlarmEventJson.valid(t0).with(TIMESTAMP, fractional), 400);
    }

    @Test
    @DisplayName("A-23 timestamp above Long.MAX_VALUE is a 400, not a 500")
    void a23_timestampAboveLongMax_isRejected() {
        BigInteger aboveLongMax = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        postAndExpectProblem(AlarmEventJson.valid(t0).with(TIMESTAMP, aboveLongMax), 400);
    }

    @Test
    @DisplayName("A-24 unknown extra field is ignored and not stored")
    void a24_unknownField_isIgnored() {
        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with("vendor", "Nokia"));

        assertStatus(response, 201);
        assertThat(onlyHistoryRow())
                .usingRecursiveComparison()
                .ignoringFields("notificationSent")
                .isEqualTo(new HistoryRow(
                        "e1", "BTS-100", "CELL_DOWN", "MAJOR", "RAISED", t0, "Cell is down", "ACCEPTED", false));
        ApiResponse active = get("/api/alarms/active");
        assertStatus(active, 200);
        assertThat(active.body()).doesNotContain("Nokia");
    }

    @ParameterizedTest(name = "{0}: {1} characters")
    @MethodSource("limitsAtTheBoundary")
    @DisplayName("A-25 value exactly at the length limit is accepted and stored whole")
    void a25_atLimit_isAccepted(String field, int length) {
        String value = "x".repeat(length);

        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with(field, value));

        assertStatus(response, 201);
        assertThat(storedValue(onlyHistoryRow(), field)).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0}: {1} characters")
    @MethodSource("limitsOverTheBoundary")
    @DisplayName("A-25 value one character over the limit is a 400, not a database error")
    void a25_overLimit_isRejected(String field, int length) {
        postAndExpectProblem(AlarmEventJson.valid(t0).with(field, "x".repeat(length)), 400);
    }

    @Test
    @DisplayName("A-25 the limit counts characters, not bytes")
    void a25_limitCountsCharactersNotBytes() {
        String cyrillic64 = "\u0436".repeat(64); // 64 characters, 128 bytes in UTF-8

        ApiResponse response = postEvent(AlarmEventJson.valid(t0).with(EVENT_ID, cyrillic64));

        assertStatus(response, 201);
        assertThat(onlyHistoryRow().eventId()).isEqualTo(cyrillic64);
    }

    @Test
    @DisplayName("A-26 spaces inside and around values are neither limited nor trimmed")
    void a26_spaces_areKeptAsReceived() {
        AlarmEventJson base = AlarmEventJson.valid(t0);

        assertStatus(postEvent(base.with(EVENT_ID, "e1")), 201);
        assertStatus(postEvent(base.with(EVENT_ID, " e1")), 201);
        assertStatus(postEvent(base.with(EVENT_ID, "e3").with(ELEMENT_ID, "BTS 100")), 201);

        List<HistoryRow> rows = db.history();
        assertThat(rows).extracting(HistoryRow::eventId).containsExactlyInAnyOrder("e1", " e1", "e3");
        assertThat(rows).extracting(HistoryRow::elementId).containsExactlyInAnyOrder("BTS-100", "BTS-100", "BTS 100");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A-27 several validation errors are all reported")
    void a27_severalErrors_areAllReported() {
        AlarmEventJson event = AlarmEventJson.valid(t0).without(ELEMENT_ID).with(ALARM_CODE, "");

        ApiResponse response = postAndExpectProblem(event, 400);

        assertThat(errorFields(response)).contains("elementId", "alarmCode");
    }

    private HistoryRow onlyHistoryRow() {
        List<HistoryRow> rows = db.history();
        assertThat(rows).as("rows of alarm_events").hasSize(1);
        return rows.get(0);
    }
}
