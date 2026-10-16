package com.se.telecomalarm.api;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON text of an alarm event for POST /api/alarms.
 * <p>
 * Deliberately independent of the application's DTOs: the request is a plain JSON string, so the tests also
 * exercise how the application's Jackson is configured. Starts from a valid event; any field can be removed or
 * replaced (including with null). Every {@code with}/{@code without} returns a new builder, so a base event can be
 * reused to derive variants.
 */
final class AlarmEventJson {

    static final String EVENT_ID = "eventId";
    static final String ELEMENT_ID = "elementId";
    static final String ALARM_CODE = "alarmCode";
    static final String SEVERITY = "severity";
    static final String STATUS = "status";
    static final String TIMESTAMP = "timestamp";
    static final String DESCRIPTION = "description";

    private final Map<String, Object> fields;

    private AlarmEventJson(Map<String, Object> fields) {
        this.fields = fields;
    }

    /**
     * A valid RAISED event for the default pair (BTS-100, CELL_DOWN) with the given timestamp (epoch ms).
     */
    static AlarmEventJson valid(long timestamp) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(EVENT_ID, "e1");
        fields.put(ELEMENT_ID, "BTS-100");
        fields.put(ALARM_CODE, "CELL_DOWN");
        fields.put(SEVERITY, "MAJOR");
        fields.put(STATUS, "RAISED");
        fields.put(TIMESTAMP, timestamp);
        fields.put(DESCRIPTION, "Cell is down");
        return new AlarmEventJson(fields);
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            return quote(text.toString());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString(); // never 1.2E+12: keep the plain digits
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c); // non-ASCII is sent as is; the body goes out as UTF-8
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    /**
     * Replaces the field, or adds it. The value is written as JSON by its type: null as {@code null},
     * a {@link CharSequence} as a string, a {@link Number} as a number (so {@code "2"} and {@code 2} differ).
     */
    AlarmEventJson with(String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put(field, value);
        return new AlarmEventJson(copy);
    }

    /**
     * Removes the field from the JSON entirely (different from sending it as null).
     */
    AlarmEventJson without(String field) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.remove(field);
        return new AlarmEventJson(copy);
    }

    String toJson() {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(valueToJson(entry.getValue()));
        }
        return json.append('}').toString();
    }

    @Override
    public String toString() {
        return toJson();
    }
}
