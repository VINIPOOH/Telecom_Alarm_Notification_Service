package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The common checks for any error response (checks O1-O3).
 * O4 (state unchanged) needs the database and O5 (no notification) needs the receiver; both live in
 * {@link ApiTestBase#postRawAndExpectProblem}.
 */
public final class ProblemAssertions {

    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "Exception", "Caused by",
            "java.", "javax.", "jakarta.", "org.springframework", "tools.jackson", "com.fasterxml", "com.se.",
            "alarm_events", "active_alarms");

    private static final Pattern STACK_FRAME = Pattern.compile("\\sat [\\w.$]+\\(");

    private static final Pattern SQL_STATEMENT =
            Pattern.compile("\\b(select|insert|update|delete)\\b[\\s\\S]*\\b(from|into|set)\\b", Pattern.CASE_INSENSITIVE);

    private ProblemAssertions() {
    }

    /**
     * Status first, so a wrong status is reported before anything about the body.
     */
    public static void assertProblem(ApiResponse response, int expectedStatus) {
        assertThat(response.status()).as("HTTP status of %s", response).isEqualTo(expectedStatus);

        // O1
        assertThat(response.mediaType()).as("O1: Content-Type of %s", response).isEqualTo("application/problem+json");

        // O2
        DocumentContext json = response.json();
        Object status = required(json, "$.status", response);
        assertThat(status).as("O2: status in the body").isInstanceOf(Number.class);
        assertThat(((Number) status).intValue()).as("O2: status in the body equals the HTTP status").isEqualTo(expectedStatus);
        assertThat(required(json, "$.title", response)).as("O2: title").asString().isNotBlank();
        assertThat(required(json, "$.detail", response)).as("O2: detail").asString().isNotBlank();

        // O3
        String body = response.body();
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            assertThat(body).as("O3: no '%s' in the error body", fragment).doesNotContain(fragment);
        }
        assertThat(STACK_FRAME.matcher(body).find()).as("O3: no stack trace frames in the error body").isFalse();
        assertThat(SQL_STATEMENT.matcher(body).find()).as("O3: no SQL in the error body").isFalse();
    }

    /**
     * The {@code field} of every element of the {@code errors} array.
     */
    static List<String> errorFields(ApiResponse response) {
        Object errors = required(response.json(), "$.errors", response);
        assertThat(errors).as("'errors' must be an array in %s", response).isInstanceOf(List.class);
        List<String> fields = new ArrayList<>();
        for (Object element : (List<?>) errors) {
            assertThat(element).as("each element of 'errors' must be an object in %s", response).isInstanceOf(Map.class);
            fields.add(String.valueOf(((Map<?, ?>) element).get("field")));
        }
        return fields;
    }

    private static Object required(DocumentContext json, String path, ApiResponse response) {
        try {
            return json.read(path);
        } catch (PathNotFoundException e) {
            throw new AssertionError(path + " is missing in " + response);
        }
    }
}
