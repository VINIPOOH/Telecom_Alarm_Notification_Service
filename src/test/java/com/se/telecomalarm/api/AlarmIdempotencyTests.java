package com.se.telecomalarm.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.se.telecomalarm.api.AlarmDb.HistoryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.se.telecomalarm.api.AlarmEventJson.ALARM_CODE;
import static com.se.telecomalarm.api.AlarmEventJson.DESCRIPTION;
import static com.se.telecomalarm.api.AlarmEventJson.ELEMENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.EVENT_ID;
import static com.se.telecomalarm.api.AlarmEventJson.SEVERITY;
import static com.se.telecomalarm.api.AlarmEventJson.STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Section B of the test-case spec: idempotency by {@code eventId}.
 * <p>
 * The "notification was dropped" variant of B-11 needs a queue of capacity 2, so a Spring context of its own: it is
 * in {@code NotificationQueueFullTests}. B-06 needs only the receiver and stays here, in the shared context.
 * <p>
 * "R(10:00, MAJOR, e1)" in the comments is the default event of {@link AlarmEventJson#valid(long)} at {@code t0}.
 */
class AlarmIdempotencyTests extends ApiTestBase {

    private static final long MINUTE = 60_000L;

    /**
     * B-09: how many identical requests are sent at the same moment.
     */
    private static final int IDENTICAL_PARALLEL_REQUESTS = 20;

    /**
     * B-09: how many times the whole scenario runs (the database is emptied before every round).
     */
    private static final int IDENTICAL_RACE_ROUNDS = 10;

    /**
     * B-10: how many times the two-request scenario runs (the database is emptied before every round).
     */
    private static final int CONFLICTING_RACE_ROUNDS = 30;

    /**
     * B-12, B-13: how long to wait until the service's INSERT is seen waiting for the test's transaction. Half of the
     * 10 s HTTP timeout of {@link #postEvent}, so the request is still alive when the test commits.
     */
    private static final Duration INSERT_WAIT_LIMIT = Duration.ofSeconds(5);

    // ---- B-01 .. B-05: a repeat of an accepted event ------------------------------------------------------------

    /**
     * The answer to an exact repeat: 200, {@code application/json}, body exactly {@code {eventId, notification}}.
     */
    private static void assertDuplicateAnswer(ApiResponse response, String eventId) {
        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        assertThat(response.jsonObject())
                .as("body of %s", response)
                .isEqualTo(Map.of("eventId", eventId, "notification", "NOT_SENT_DUPLICATE"));
    }

    @Test
    @DisplayName("B-01 exact replay returns 200 and keeps a single history row")
    void exactReplay_returns200_andKeepsSingleHistoryRow() {
        AlarmEventJson event = AlarmEventJson.valid(t0); // R(10:00, MAJOR, e1)
        givenAccepted(event);
        AlarmDb.Snapshot stored = stateIgnoringDeliveryFlag();

        ApiResponse replay = postEvent(event);

        assertDuplicateAnswer(replay, "e1");
        assertThat(stateIgnoringDeliveryFlag()).as("history and active alarms after the replay").isEqualTo(stored);
        assertThat(onlyHistoryRow()).isEqualTo(expectedRaised("MAJOR"));
    }

    @Test
    @DisplayName("B-02 same eventId with different data is a 409 that does not reveal the stored record")
    void sameEventIdWithDifferentSeverity_returns409_andDoesNotRevealStoredRecord() {
        givenAccepted(AlarmEventJson.valid(t0)); // R(10:00, MAJOR, e1)

        ApiResponse conflict = postAndExpectProblem(AlarmEventJson.valid(t0).with(SEVERITY, "CRITICAL"), 409);

        assertThat(conflict.body())
                .as("the 409 body must not contain values of the stored record")
                .doesNotContain("MAJOR", "BTS-100", "CELL_DOWN", "Cell is down", String.valueOf(t0));
        assertThat(onlyHistoryRow()).as("the first record stays as it was").isEqualTo(expectedRaised("MAJOR"));
    }

    @Test
    @DisplayName("B-03 a difference in description only is still a 409")
    void sameEventIdWithDifferentDescription_returns409() {
        givenAccepted(AlarmEventJson.valid(t0)); // description "Cell is down"

        postAndExpectProblem(AlarmEventJson.valid(t0).with(DESCRIPTION, "Another description"), 409);

        assertThat(onlyHistoryRow().description()).as("the first record stays as it was").isEqualTo("Cell is down");
    }

    @Test
    @DisplayName("B-04 description absent in the first request and null in the repeat is an exact replay")
    void descriptionAbsentThenNull_isExactReplay_returns200() {
        givenAccepted(AlarmEventJson.valid(t0).without(DESCRIPTION));
        AlarmDb.Snapshot stored = stateIgnoringDeliveryFlag();

        ApiResponse replay = postEvent(AlarmEventJson.valid(t0).with(DESCRIPTION, null));

        assertDuplicateAnswer(replay, "e1");
        assertThat(stateIgnoringDeliveryFlag()).as("history and active alarms after the replay").isEqualTo(stored);
        assertThat(onlyHistoryRow().description()).isNull();
    }

    @Test
    @DisplayName("B-05 an extra unknown field in the repeat is ignored: still an exact replay")
    void replayWithExtraUnknownField_isExactReplay_returns200() {
        AlarmEventJson event = AlarmEventJson.valid(t0);
        givenAccepted(event);
        AlarmDb.Snapshot stored = stateIgnoringDeliveryFlag();

        ApiResponse replay = postEvent(event.with("vendor", "x"));

        assertDuplicateAnswer(replay, "e1");
        assertThat(stateIgnoringDeliveryFlag()).as("history and active alarms after the replay").isEqualTo(stored);
        assertThat(onlyHistoryRow()).isEqualTo(expectedRaised("MAJOR"));
    }

    // ---- B-07, B-08: a repeat of a rejected event, the same eventId for another pair -----------------------------

    @Test
    @DisplayName("B-06 an exact replay does not send a second notification")
    void exactReplay_sendsNoSecondNotification() {
        AlarmEventJson event = AlarmEventJson.valid(t0).with(EVENT_ID, "B06-e1");
        givenAccepted(event);

        assertDuplicateAnswer(postEvent(event), "B06-e1");

        // Not "no notification" but "exactly one": the first one must arrive, so the check means something.
        awaitNotificationQueueEmpty();
        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly("B06-e1");
    }

    @Test
    @DisplayName("B-07 repeat of a rejected CLEARED is a 409 and keeps a single REJECTED row")
    void replayOfRejectedCleared_returns409_andKeepsSingleRejectedRow() {
        // C(10:05, e2) for a pair that never had a RAISED: rejected, but stored with acceptance = REJECTED.
        AlarmEventJson cleared = AlarmEventJson.valid(t0 + 5 * MINUTE).with(EVENT_ID, "e2").with(STATUS, "CLEARED");
        ProblemAssertions.assertProblem(postEvent(cleared), 409); // O1-O3 only: this first request does write the REJECTED row
        HistoryRow rejected = new HistoryRow(
                "e2", "BTS-100", "CELL_DOWN", "MAJOR", "CLEARED", t0 + 5 * MINUTE, "Cell is down", "REJECTED", false);
        assertThat(onlyHistoryRow()).as("given: the rejected CLEARED is stored").isEqualTo(rejected);

        postAndExpectProblem(cleared, 409); // O1-O4: the repeat changes nothing

        assertThat(onlyHistoryRow()).isEqualTo(rejected);
        assertThat(db.activeEventIds()).isEmpty();
    }

    // ---- B-09, B-10: the same eventId arrives in parallel -------------------------------------------------------

    @Test
    @DisplayName("B-08 same eventId for another pair is a 409 and creates nothing for that pair")
    void sameEventIdForAnotherPair_returns409_andCreatesNothingForThatPair() {
        givenAccepted(AlarmEventJson.valid(t0)); // e1 for (BTS-100, CELL_DOWN)
        AlarmEventJson sameIdOtherPair = AlarmEventJson.valid(t0).with(ELEMENT_ID, "BTS-200").with(ALARM_CODE, "HIGH_CPU");

        postAndExpectProblem(sameIdOtherPair, 409);

        // The only history row is still the first pair's, so the second pair has no history. An active row of the
        // second pair would need a history row of it (active_alarms.event_id is a foreign key), so it has no active alarm either.
        assertThat(onlyHistoryRow()).isEqualTo(expectedRaised("MAJOR"));
    }

    @RepeatedTest(value = IDENTICAL_RACE_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("B-09 identical requests in parallel: exactly one 201, the rest 200, one history row")
    void identicalRequestsInParallel_exactlyOneGets201_othersGet200_oneHistoryRow() {
        AlarmEventJson event = AlarmEventJson.valid(t0); // R(10:00, MAJOR, e1), the same in every request
        Callable<ApiResponse> send = () -> postEvent(event);
        List<Callable<ApiResponse>> requests = Collections.nCopies(IDENTICAL_PARALLEL_REQUESTS, send);

        List<ApiResponse> responses = SimultaneousRequests.runTogether(requests);

        Map<Integer, Long> countByStatus = responses.stream()
                .collect(Collectors.groupingBy(ApiResponse::status, TreeMap::new, Collectors.counting()));
        assertThat(countByStatus)
                .as("how many of the %d parallel requests got which HTTP status; responses: %s",
                    IDENTICAL_PARALLEL_REQUESTS, responses)
                .isEqualTo(Map.of(201, 1L, 200, (long) IDENTICAL_PARALLEL_REQUESTS - 1));
        // Exactly one request queued a notification, all the others say it is a duplicate.
        for (ApiResponse response : responses) {
            String notification = response.status() == 201 ? "QUEUED" : "NOT_SENT_DUPLICATE";
            assertThat(response.jsonObject())
                    .as("body of %s", response)
                    .isEqualTo(Map.of("eventId", "e1", "notification", notification));
        }
        assertThat(onlyHistoryRow()).isEqualTo(expectedRaised("MAJOR"));
        // How many notifications the receiver really got: the separate test below, so that this one stays independent
        // of the notification slice.
    }

    @RepeatedTest(value = IDENTICAL_RACE_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("B-09 (notification part) identical requests in parallel give exactly one notification")
    void identicalRequestsInParallel_giveExactlyOneNotification() {
        AlarmEventJson event = AlarmEventJson.valid(t0).with(EVENT_ID, "B09-e1");
        Callable<ApiResponse> send = () -> postEvent(event);

        List<ApiResponse> responses = SimultaneousRequests.runTogether(Collections.nCopies(IDENTICAL_PARALLEL_REQUESTS, send));

        assertThat(responses).extracting(ApiResponse::status).as("HTTP statuses").containsOnly(201, 200);
        awaitNotificationQueueEmpty();
        assertThat(receivedEventIds()).as("notifications the receiver got").containsExactly("B09-e1");
    }

    // ---- B-11: the answer to a repeat does not depend on the earlier notification -------------------------------

    @RepeatedTest(value = CONFLICTING_RACE_ROUNDS, name = RepeatedTest.LONG_DISPLAY_NAME)
    @DisplayName("B-10 same eventId with different data in parallel: one 201, one 409, the winner's data is stored")
    void sameEventIdWithDifferentDataInParallel_oneGets201_otherGets409_winnerDataIsStored() {
        AlarmEventJson major = AlarmEventJson.valid(t0).with(SEVERITY, "MAJOR");       // R(10:00, MAJOR, e1)
        AlarmEventJson critical = AlarmEventJson.valid(t0).with(SEVERITY, "CRITICAL"); // R(10:00, CRITICAL, e1)
        List<Callable<ApiResponse>> requests = List.of(() -> postEvent(major), () -> postEvent(critical));

        List<ApiResponse> responses = SimultaneousRequests.runTogether(requests);
        ApiResponse majorAnswer = responses.get(0);
        ApiResponse criticalAnswer = responses.get(1);

        assertThat(List.of(majorAnswer.status(), criticalAnswer.status()))
                .as("HTTP statuses of the MAJOR and CRITICAL requests; MAJOR got: %s; CRITICAL got: %s",
                    majorAnswer, criticalAnswer)
                .containsExactlyInAnyOrder(201, 409);
        boolean majorWon = majorAnswer.status() == 201;
        ApiResponse winner = majorWon ? majorAnswer : criticalAnswer;
        ApiResponse loser = majorWon ? criticalAnswer : majorAnswer;

        assertThat(winner.jsonObject()).as("body of %s", winner).isEqualTo(Map.of("eventId", "e1", "notification", "QUEUED"));
        ProblemAssertions.assertProblem(loser, 409);
        assertThat(onlyHistoryRow())
                .as("the stored record must be the winner's (%s)", majorWon ? "MAJOR" : "CRITICAL")
                .isEqualTo(expectedRaised(majorWon ? "MAJOR" : "CRITICAL"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "earlier notification delivered (notification_sent) = {0}")
    @ValueSource(booleans = { false, true })
    @DisplayName("B-11 repeat of an accepted event always answers 200 NOT_SENT_DUPLICATE, whatever happened to the earlier notification")
    void replayOfAcceptedEvent_alwaysAnswersNotSentDuplicate_whateverTheEarlierNotificationState(boolean delivered) {
        AlarmEventJson event = AlarmEventJson.valid(t0);
        givenAccepted(event);
        // "Delivered" is notification_sent = true (SPEC section 6); the flag is set directly so that both states are
        // certain. The sender writes the flag too, so first wait until it has finished: then nothing overwrites ours.
        // The variant "the notification was dropped because the queue was full" is in NotificationQueueFullTests.
        awaitNotificationQueueEmpty();
        int updatedRows = jdbc.update("UPDATE alarm_events SET notification_sent = ? WHERE event_id = ?", delivered, "e1");
        assertThat(updatedRows).as("given: the accepted event e1 is stored in alarm_events").isEqualTo(1);

        assertDuplicateAnswer(postEvent(event), "e1");
        assertDuplicateAnswer(postEvent(event), "e1");
    }

    // ---- B-12, B-13: the eventId is taken by a transaction that commits while our INSERT waits ------------------

    @Test
    @DisplayName("B-12 the same event committed by another transaction while our INSERT waits on it: 200, a repeat")
    void sameEventCommittedWhileInsertWaits_returns200_asExactRepeat() throws Exception {
        HistoryRow sameEvent = expectedRaised("MAJOR"); // exactly what the request carries: R(10:00, MAJOR, e1)
        int notificationsBefore = receivedNotifications().size();

        ApiResponse response = postWhileAnotherTransactionHoldsUncommitted(sameEvent, AlarmEventJson.valid(t0));

        assertDuplicateAnswer(response, "e1");
        assertThat(db.snapshot())
                .as("only the other transaction's row; the repeat wrote nothing (that transaction stored no active row)")
                .isEqualTo(new AlarmDb.Snapshot(List.of(sameEvent), List.of()));
        assertNoNotificationSince(notificationsBefore);
    }

    @Test
    @DisplayName("B-13 the same eventId of another pair committed by another transaction while our INSERT waits on it: 409")
    void sameEventIdOfAnotherPairCommittedWhileInsertWaits_returns409() throws Exception {
        HistoryRow otherPair = new HistoryRow(
                "e1", "BTS-200", "HIGH_CPU", "MAJOR", "RAISED", t0, "Other pair", "ACCEPTED", false);
        int notificationsBefore = receivedNotifications().size();

        ApiResponse response = postWhileAnotherTransactionHoldsUncommitted(otherPair, AlarmEventJson.valid(t0));

        ProblemAssertions.assertProblem(response, 409);
        assertThat(db.snapshot())
                .as("only the other pair's row; (BTS-100, CELL_DOWN) has neither history nor an active alarm")
                .isEqualTo(new AlarmDb.Snapshot(List.of(otherPair), List.of()));
        assertNoNotificationSince(notificationsBefore);
    }

    /**
     * B-12, B-13: the race of SPEC section 2, step 3, made deterministic. The test plays the "other" transaction
     * itself instead of hoping that two requests overlap:
     * <ol>
     *   <li>a connection of its own, outside the application, inserts {@code row} and does NOT commit;</li>
     *   <li>the request is sent in the background; the service takes its pair lock (the test took none), its reads
     *       do not see the uncommitted row, and its {@code INSERT ... ON CONFLICT} meets the uncommitted key in the
     *       unique index: PostgreSQL makes it wait for the test's transaction;</li>
     *   <li>the test waits until it SEES that wait in {@code pg_stat_activity}, so the overlap is proven, not assumed;</li>
     *   <li>the test commits: the waiting INSERT answers 0 rows, and the service decides by the row now stored.</li>
     * </ol>
     * What it catches: a plain INSERT, {@code persist} or {@code save()} instead of {@code ON CONFLICT} (a unique
     * violation, HTTP 500); isolation raised to REPEATABLE READ (the row is committed after the snapshot, PostgreSQL
     * answers ON CONFLICT with a serialization failure, HTTP 500); a read of the stored row that does not see the
     * commit.
     * <p>
     * In production the same pair cannot get here: the other transaction would hold the pair lock and ours would wait
     * BEFORE its reads. The test takes no pair lock, so B-12 (same data, same pair) is not a reachable race; it checks
     * that the "0 rows" path also answers an exact repeat correctly. B-13 (another pair) is the reachable one.
     * <p>
     * If anything fails before the commit, the transaction is rolled back in {@code finally}: an open transaction
     * holding a row would block the TRUNCATE of the next test.
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

    /**
     * The "given" step: the event is sent and accepted for the first time (201).
     */
    private void givenAccepted(AlarmEventJson event) {
        ApiResponse response = postEvent(event);
        assertStatus(response, 201);
    }

    /**
     * The stored default event {@code R(10:00, <severity>, e1)} of the default pair, accepted.
     */
    private HistoryRow expectedRaised(String severity) {
        return new HistoryRow(
                "e1", "BTS-100", "CELL_DOWN", severity, "RAISED", t0, "Cell is down", "ACCEPTED", false);
    }

    private HistoryRow onlyHistoryRow() {
        List<HistoryRow> rows = historyIgnoringDeliveryFlag();
        assertThat(rows).as("rows of alarm_events").hasSize(1);
        return rows.get(0);
    }

    /**
     * Everything the service stores, with {@code notification_sent} always read as false: the notification
     * sender runs in the background and may set the flag between two reads, which is not what these tests check.
     */
    private AlarmDb.Snapshot stateIgnoringDeliveryFlag() {
        return new AlarmDb.Snapshot(historyIgnoringDeliveryFlag(), db.activeEventIds());
    }

    private List<HistoryRow> historyIgnoringDeliveryFlag() {
        return db.history().stream()
                .map(row -> new HistoryRow(
                        row.eventId(), row.elementId(), row.alarmCode(), row.severity(), row.status(),
                        row.timestamp(), row.description(), row.acceptance(), false))
                .toList();
    }
}
