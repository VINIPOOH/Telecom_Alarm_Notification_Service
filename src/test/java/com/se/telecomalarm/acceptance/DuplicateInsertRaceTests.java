package com.se.telecomalarm.acceptance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.se.telecomalarm.api.AlarmDb;
import com.se.telecomalarm.api.AlarmDb.HistoryRow;
import com.se.telecomalarm.api.AlarmEventJson;
import com.se.telecomalarm.api.ApiResponse;
import com.se.telecomalarm.api.ApiTestBase;
import com.se.telecomalarm.api.ProblemAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The race at the {@code INSERT ... ON CONFLICT DO NOTHING} step (README, Concurrency guarantees, step 3), made
 * deterministic: the {@code eventId} is taken by another transaction that commits while the service's INSERT waits
 * on it. Test cases B-12 and B-13. Assignment, sections 2 and 3: "the eventId must be globally unique" and "ensure
 * concurrent requests cannot corrupt the state".
 * <p>
 * Two requests started together would overlap only by chance. Here the test plays the other transaction itself:
 * <ol>
 *   <li>a connection of its own, outside the application, inserts a row and does NOT commit;</li>
 *   <li>the request is sent in the background; the service takes its pair lock (the test took none), its reads do
 *       not see the uncommitted row, and its {@code INSERT ... ON CONFLICT} meets the uncommitted key in the unique
 *       index: PostgreSQL makes it wait for the test's transaction;</li>
 *   <li>the test waits until it SEES that wait in {@code pg_stat_activity}, so the overlap is proven, not assumed;</li>
 *   <li>the test commits: the waiting INSERT answers 0 rows, and the service decides by the row now stored.</li>
 * </ol>
 * No sleeps: the interleaving happens on every run.
 * <p>
 * What it catches: a plain INSERT, {@code persist} or {@code save()} instead of {@code ON CONFLICT} (a unique
 * violation, HTTP 500); isolation raised to REPEATABLE READ (the row is committed after the snapshot, PostgreSQL
 * answers ON CONFLICT with a serialization failure, HTTP 500); a read of the stored row that does not see the commit.
 * <p>
 * In production the same pair cannot get here: the other transaction would hold the pair lock, and ours would wait
 * BEFORE its reads. The test takes no pair lock, so B-12 (same data, same pair) is not a reachable race; it checks
 * that the "0 rows" path also answers an exact repeat correctly. B-13 (another pair) is the reachable one.
 */
@DisplayName("eventId taken by another transaction while the service's INSERT waits on it")
class DuplicateInsertRaceTests extends ApiTestBase {

    /**
     * How long to wait until the service's INSERT is seen waiting for the test's transaction. Half of the 10 s HTTP
     * timeout of {@link #postEvent}, so the request is still alive when the test commits.
     */
    private static final Duration INSERT_WAIT_LIMIT = Duration.ofSeconds(5);

    private static void insertUncommitted(Connection connection, HistoryRow row) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                                                                            INSERT INTO alarm_events
                                                                            	(event_id, element_id, alarm_code, severity, status, event_timestamp, description, acceptance,
                                                                            	 notification_sent)
                                                                            VALUES (?, ?, ?, CAST(? AS alarm_severity), CAST(? AS alarm_status), ?, ?, CAST(? AS event_acceptance), ?)
                                                                            """)) {
            insert.setString(1, row.eventId());
            insert.setString(2, row.elementId());
            insert.setString(3, row.alarmCode());
            insert.setString(4, row.severity());
            insert.setString(5, row.status());
            insert.setLong(6, row.timestamp());
            insert.setString(7, row.description());
            insert.setString(8, row.acceptance());
            insert.setBoolean(9, row.notificationSent());
            assertThat(insert.executeUpdate()).as("given: the other transaction inserted its row").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("B-12 the same event, committed by the other transaction: 200, an exact repeat, nothing written")
    void givenSameEventUncommittedInAnotherTransaction_whenItCommitsDuringOurInsert_thenTheAnswerIsAnExactRepeat()
            throws Exception {
        // given: another transaction holds exactly the event the request carries, R(10:00, MAJOR, e1), uncommitted
        HistoryRow sameEvent = new HistoryRow(
                "e1", "BTS-100", "CELL_DOWN", "MAJOR", "RAISED", t0, "Cell is down", "ACCEPTED", false);
        int notificationsBefore = receivedNotifications().size();

        // when
        ApiResponse response = postWhileAnotherTransactionHoldsUncommitted(sameEvent, AlarmEventJson.valid(t0));

        // then
        assertStatus(response, 200);
        assertThat(response.jsonObject()).isEqualTo(Map.of("eventId", "e1", "notification", "NOT_SENT_DUPLICATE"));
        assertThat(db.snapshot())
                .as("only the other transaction's row; the repeat wrote nothing (that transaction stored no active row)")
                .isEqualTo(new AlarmDb.Snapshot(List.of(sameEvent), List.of()));
        assertNoNotificationSince(notificationsBefore);
    }

    @Test
    @DisplayName("B-13 the same eventId of another alarm, committed by the other transaction: 409, nothing written")
    void givenSameEventIdOfAnotherAlarmUncommitted_whenItCommitsDuringOurInsert_thenTheAnswerIsAConflict()
            throws Exception {
        // given: another transaction holds eventId e1 for another alarm (BTS-200, HIGH_CPU), uncommitted
        HistoryRow otherAlarm = new HistoryRow(
                "e1", "BTS-200", "HIGH_CPU", "MAJOR", "RAISED", t0, "Other pair", "ACCEPTED", false);
        int notificationsBefore = receivedNotifications().size();

        // when: the request carries e1 for (BTS-100, CELL_DOWN)
        ApiResponse response = postWhileAnotherTransactionHoldsUncommitted(otherAlarm, AlarmEventJson.valid(t0));

        // then
        ProblemAssertions.assertProblem(response, 409);
        assertThat(db.snapshot())
                .as("only the other alarm's row; (BTS-100, CELL_DOWN) has neither history nor an active alarm")
                .isEqualTo(new AlarmDb.Snapshot(List.of(otherAlarm), List.of()));
        assertNoNotificationSince(notificationsBefore);
    }

    /**
     * Steps 1-4 of the class comment. If anything fails before the commit, the transaction is rolled back in
     * {@code finally}: an open transaction holding a row would block the TRUNCATE of the next test.
     */
    private ApiResponse postWhileAnotherTransactionHoldsUncommitted(HistoryRow row, AlarmEventJson request)
            throws Exception {
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            boolean committed = false;
            try {
                insertUncommitted(other, row);
                CompletableFuture<ApiResponse> answer = CompletableFuture.supplyAsync(() -> postEvent(request));

                awaitServiceInsertWaitingOnAnotherTransaction();
                assertThat(answer).as("the request must still be waiting on the uncommitted row").isNotDone();

                other.commit();
                committed = true;
                return answer.get(INSERT_WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
            } finally {
                if (!committed) {
                    other.rollback();
                }
            }
        }
    }

    /**
     * Waits until a backend runs an insert into alarm_events and waits for a transaction to end: wait_event_type
     * {@code Lock}, wait_event {@code transactionid}, which is how PostgreSQL waits on an uncommitted key in a unique
     * index. That backend can only be the service's: the test's own insert has already finished, its session is idle
     * in its transaction.
     * <p>
     * The statement is matched by "insert into alarm_events" only, case-insensitively, NOT by the rest of the
     * service's SQL (such as {@code ON CONFLICT}): the precondition must hold for any way of inserting, so that a
     * broken insert (a plain INSERT, Hibernate's own "insert into" of {@code persist} or {@code save()}) still reaches
     * the wait and fails on the answer, not here.
     */
    private void awaitServiceInsertWaitingOnAnotherTransaction() {
        await().atMost(INSERT_WAIT_LIMIT).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                                                                                                     SELECT count(*) FROM pg_stat_activity
                                                                                                     WHERE wait_event_type = 'Lock'
                                                                                                       AND wait_event = 'transactionid'
                                                                                                       AND query ILIKE '%insert into alarm_events%'
                                                                                                     """, Long.class))
                .as("backends whose insert into alarm_events waits for the test's transaction")
                .isEqualTo(1L));
    }
}
