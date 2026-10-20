package com.se.telecomalarm.api;

import com.se.telecomalarm.alarm.service.active.ActiveAlarmReaderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * An unexpected exception (a bug, a failed database call) is a 500 problem detail with a fixed text: its message,
 * class name and SQL never reach the client (checks O1-O3 of {@link ProblemAssertions}).
 * <p>
 * Own Spring context, by necessity: the read service is replaced by a mock that throws, so the test does not depend on
 * a way to break the real application.
 */
class InternalErrorTests extends ApiTestBase {

    private static final String SECRET = "SELECT * FROM alarm_events WHERE element_id = 'BTS-SECRET'";

    @MockitoBean
    ActiveAlarmReaderService activeAlarms;

    @Test
    @DisplayName("an unexpected exception is a 500 problem detail that reveals nothing of the exception")
    void unexpectedException_is500ProblemDetail_revealingNothing() {
        when(activeAlarms.findAllActiveAlarms()).thenThrow(new IllegalStateException(SECRET));

        ApiResponse response = get("/api/alarms/active");

        ProblemAssertions.assertProblem(response, 500);
        assertThat(response.body()).as("body of %s", response).doesNotContain("BTS-SECRET");
    }
}
