package com.se.telecomalarm.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.se.telecomalarm.alarm.service.notification.NotificationQueue;
import com.se.telecomalarm.api.NotificationRecorder.ReceivedNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base of the API tests: the whole application on a random port with a real PostgreSQL in Testcontainers,
 * a WireMock server as the notification receiver, a plain JDK HTTP client, and direct SQL access to the tables.
 * <p>
 * All subclasses share one Spring context (same configuration), so one container and one WireMock server serve the
 * whole run. {@code @EnableWireMock} is {@code @Inherited}, and every subclass gets the same WireMock context
 * customizer (equal by its configuration), so the subclasses still map to one cached context. A subclass that changes
 * properties gets its own context, and with it its own WireMock server.
 * <p>
 * The database is emptied before every test; tests are not {@code @Transactional}.
 * <p>
 * <b>The receiver.</b> wiremock-spring-boot starts the server before the context and puts its address into the
 * {@code wiremock.server.httpsBaseUrl} property; {@code src/test/resources/config/application.yaml} builds the
 * notification address from it. The receiver speaks <b>HTTPS only</b> (notifications go over TLS, and the tests
 * take the same path as production): the plain HTTP port is switched off ({@code port = -1}), the
 * HTTPS port is random ({@code httpsPort = 0}), and the server certificate is the self-signed local-development one
 * for {@code localhost} ({@code src/test/resources/tls/}). The application trusts it through the SSL bundle
 * {@code notification-receiver} of the main application.yaml, so the TLS handshake, the certificate check and the
 * host name check all really run. Before every test wiremock-spring-boot calls {@link WireMockServer#resetAll()}
 * (stubs and request journal), then {@link #resetState()} puts back the default stub (200) and clears the
 * {@link NotificationRecorder}. When several stubs match a request, WireMock uses the most recently added one, so a
 * test changes the receiver simply by adding a stub ({@link #receiverAnswers}, {@link #receiverAnswersFor}).
 * <p>
 * <b>Timing.</b> The read timeout of the notification client is the production one. Every
 * WireMock delay and every wait limit is derived from it ({@link #readTimeout()}), never written as a number in a test.
 * <p>
 * <b>Waiting for "nothing more will be sent".</b> {@link NotificationQueue#pendingCount()} counts the notifications
 * waiting in the queue plus the ones being sent; a place is given back only after the send attempt and the
 * {@code notification_sent} update. So {@code pendingCount() == 0} means: every notification queued so far has
 * reached the receiver (or failed) and its flag is written. Checks of the kind "no notification" and
 * "{@code notification_sent} stayed false" wait for that state instead of sleeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableWireMock(@ConfigureWireMock(
        extensions = NotificationRecorder.class,
        port = -1,
        httpsPort = 0,
        // A file path or, if there is no such file, a classpath resource (wiremock-spring-boot): here the classpath.
        keystorePath = "tls/notification-receiver-dev.p12",
        keystoreType = "PKCS12",
        keystorePassword = "wiremock-dev-only",
        keyManagerPassword = "wiremock-dev-only"))
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestBase {

    protected static final String JSON = "application/json";

    /**
     * The path of the receiver endpoint: what the client adds to {@code spring.http.serviceclient.notifications.base-url}
     * ({@code @PostExchange} of {@code NotificationClient}). Written here again on purpose: it is the contract with the
     * receiver (wiremock/mappings), so a change of the path in the client must fail the tests.
     */
    protected static final String NOTIFICATIONS_PATH = "/external/notifications";

    /**
     * E-04 / E-05: "the 201 comes back fast". The request thread must not wait for the receiver at all, so the answer
     * is far below the read timeout; 1 s leaves room for a slow test machine.
     */
    protected static final Duration FAST_ANSWER = Duration.ofSeconds(1);

    private static final String READ_TIMEOUT_PROPERTY = "spring.http.serviceclient.notifications.read-timeout";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @Autowired
    protected JdbcTemplate jdbc;
    /**
     * The notification receiver of this Spring context.
     */
    @InjectWireMock
    protected WireMockServer receiver;
    protected AlarmDb db;
    /**
     * The "10:00" of the test cases: one hour ago by the real clock. Other times are offsets from it,
     * so every ordinary event is in the past and passes the "not too far in the future" check.
     */
    protected long t0;
    @LocalServerPort
    private int port;
    @Autowired
    private Environment environment;
    /**
     * Asked only {@link NotificationQueue#pendingCount()}: the one deliberate look inside the application.
     */
    @Autowired
    private NotificationQueue notificationQueue;

    // ---- before and after each test ---------------------------------------------------------------------------------

    @BeforeEach
    void resetState() {
        db = new AlarmDb(jdbc);
        db.truncate();
        t0 = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        // resetAll() before this method removed every stub; without one the receiver would answer 404.
        receiverAnswers(ok());
        NotificationRecorder.clear();
    }

    /**
     * Waits until the application has nothing left to send, so no notification of this test can reach the receiver
     * or touch the database during the next test (see {@link #awaitNotificationQueueEmpty}). First the receiver is
     * released (answers 200 at once): a notification already on the wire still waits for its own stub, at most the
     * read timeout, but those queued behind it no longer hang one read timeout each.
     */
    @AfterEach
    void drainNotificationQueue() {
        receiverAnswers(ok());
        awaitNotificationQueueEmpty(notificationWaitLimit(), "after the test");
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------------

    private static ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponse(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type"),
                    response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Request failed: " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + request.method() + " " + request.uri(), e);
        }
    }

    protected ApiResponse postEvent(AlarmEventJson event) {
        return postRaw(event.toJson(), JSON);
    }

    /**
     * POST /api/alarms with any body; a null contentType sends no Content-Type header at all.
     */
    protected ApiResponse postRaw(String body, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/alarms"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return send(request.build());
    }

    protected ApiResponse get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    protected static void assertStatus(ApiResponse response, int expectedStatus) {
        assertThat(response.status()).as("HTTP status of %s", response).isEqualTo(expectedStatus);
    }

    // ---- common checks for an error response (O1-O5) ----------------------------------------------------------------

    protected ApiResponse postAndExpectProblem(AlarmEventJson event, int expectedStatus) {
        return postRawAndExpectProblem(event.toJson(), JSON, expectedStatus);
    }

    /**
     * Sends the request and checks O1-O3 (problem+json body without internals), O4 (history and active alarms
     * exactly as before the request) and O5 (no notification sent).
     * <p>
     * The queue is drained BEFORE the "before" values are taken: a notification of the test's preparation (for
     * example the accepted {@code e1} of B-08) could otherwise arrive, or set its {@code notification_sent}, between
     * "before" and "after" and look like a change caused by this request.
     */
    protected ApiResponse postRawAndExpectProblem(String body, String contentType, int expectedStatus) {
        awaitNotificationQueueEmpty();
        int notificationsBefore = receivedNotifications().size();
        AlarmDb.Snapshot before = db.snapshot();

        ApiResponse response = postRaw(body, contentType);

        ProblemAssertions.assertProblem(response, expectedStatus);
        assertThat(db.snapshot()).as("O4: history and active alarms are unchanged").isEqualTo(before);
        assertNoNotificationSince(notificationsBefore);
        return response;
    }

    /**
     * O5: waits until the application has nothing left to send, then checks that the receiver
     * got no request since the count was taken. The caller takes the count after {@link #awaitNotificationQueueEmpty()},
     * so earlier notifications are already in it.
     */
    protected void assertNoNotificationSince(int notificationsBefore) {
        awaitNotificationQueueEmpty();
        assertThat(receivedNotifications())
                .as("O5: no notification for this request; the receiver had %d before it", notificationsBefore)
                .hasSize(notificationsBefore);
    }

    // ---- the receiver: stubs ----------------------------------------------------------------------------------------

    /**
     * A 200 that comes after {@code delay}.
     */
    protected static ResponseDefinitionBuilder okAfter(Duration delay) {
        return ok().withFixedDelay(Math.toIntExact(delay.toMillis()));
    }

    /**
     * From now on every notification gets this answer (the newest matching stub wins).
     */
    protected void receiverAnswers(ResponseDefinitionBuilder response) {
        receiver.stubFor(post(urlEqualTo(NOTIFICATIONS_PATH)).willReturn(response));
    }

    /**
     * From now on the notifications whose JSON field {@code jsonPath} equals {@code value} get this answer.
     */
    protected void receiverAnswersFor(String jsonPath, String value, ResponseDefinitionBuilder response) {
        receiver.stubFor(post(urlEqualTo(NOTIFICATIONS_PATH))
                                 .withRequestBody(matchingJsonPath(jsonPath, equalTo(value)))
                                 .willReturn(response));
    }

    // ---- timing, all derived from the configured read timeout -------------------------------------------------------

    /**
     * {@code spring.http.serviceclient.notifications.read-timeout}, bound with Spring Boot's own rules ("5s").
     */
    protected Duration readTimeout() {
        return Binder.get(environment).bind(READ_TIMEOUT_PROPERTY, Duration.class)
                .orElseThrow(() -> new IllegalStateException(READ_TIMEOUT_PROPERTY + " is not set"));
    }

    /**
     * "Slow, but delivered": half of the read timeout, so the sender still gets the answer with a wide margin.
     */
    protected Duration slowButDeliveredDelay() {
        return readTimeout().dividedBy(2);
    }

    /**
     * "Hung": twice the read timeout, so the sender gives up long before the receiver would answer.
     */
    protected Duration hungDelay() {
        return readTimeout().multipliedBy(2);
    }

    /**
     * Upper bound of every wait on notifications: twice the read timeout. Any single send ends within the read timeout
     * (answer or time-out), so a correct application finishes well inside it.
     */
    protected Duration notificationWaitLimit() {
        return readTimeout().multipliedBy(2);
    }

    // ---- the receiver: what arrived ---------------------------------------------------------------------------------

    /**
     * The notifications the receiver got in this test (POST to the endpoint), in the order of arrival.
     */
    protected List<ReceivedNotification> receivedNotifications() {
        return NotificationRecorder.received().stream()
                .filter(request -> "POST".equals(request.method()) && NOTIFICATIONS_PATH.equals(request.url()))
                .toList();
    }

    /**
     * The eventIds of {@link #receivedNotifications()}, in the order of arrival.
     */
    protected List<String> receivedEventIds() {
        return receivedNotifications().stream().map(ReceivedNotification::eventId).toList();
    }

    /**
     * Waits until the receiver has got at least {@code count} notifications; returns all of them.
     */
    protected List<ReceivedNotification> awaitNotifications(int count) {
        AtomicReference<List<ReceivedNotification>> result = new AtomicReference<>();
        await("the receiver gets " + count + " notification(s)")
                .atMost(notificationWaitLimit())
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    List<ReceivedNotification> received = receivedNotifications();
                    assertThat(received).as("notifications received so far").hasSizeGreaterThanOrEqualTo(count);
                    result.set(received);
                });
        return result.get();
    }

    /**
     * Waits until the notification of this event has arrived; returns it (the first one, if several).
     */
    protected ReceivedNotification awaitNotificationOf(String eventId) {
        AtomicReference<ReceivedNotification> result = new AtomicReference<>();
        await("the receiver gets the notification of " + eventId)
                .atMost(notificationWaitLimit())
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    List<ReceivedNotification> received = receivedNotifications();
                    ReceivedNotification found = received.stream()
                            .filter(notification -> eventId.equals(notification.eventId()))
                            .findFirst().orElse(null);
                    assertThat(found).as("notification of %s among %s", eventId, received).isNotNull();
                    result.set(found);
                });
        return result.get();
    }

    // ---- the application's queue ------------------------------------------------------------------------------------

    protected void awaitNotificationQueueEmpty() {
        awaitNotificationQueueEmpty(notificationWaitLimit(), "before checking the notifications");
    }

    /**
     * Waits until {@link NotificationQueue#pendingCount()} is 0; fails with a clear message when the limit passes.
     * Polled every 20 ms, first check at once.
     */
    protected void awaitNotificationQueueEmpty(Duration limit, String when) {
        await("the notification queue becomes empty " + when)
                .atMost(limit)
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(notificationQueue.pendingCount())
                        .as("NotificationQueue.pendingCount() %s: still not 0 after up to %s; notifications left in "
                                    + "the queue would reach the receiver and the database during the next test", when, limit)
                        .isZero());
    }

    // ---- notification_sent ------------------------------------------------------------------------------------------

    /**
     * {@code notification_sent = true} is set by the sender after a 2xx; waits for it.
     */
    protected void awaitNotificationSent(String eventId) {
        await("notification_sent of " + eventId + " becomes true")
                .atMost(notificationWaitLimit())
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(db.notificationSent(eventId))
                        .as("notification_sent of %s", eventId).isTrue());
    }

    /**
     * {@code notification_sent = false} can only be checked when nothing will change it any more: first the queue
     * must be empty, then the flag is read once.
     */
    protected void assertNotificationNotSent(String... eventIds) {
        awaitNotificationQueueEmpty();
        for (String eventId : eventIds) {
            assertThat(db.notificationSent(Objects.requireNonNull(eventId)))
                    .as("notification_sent of %s once the queue is empty", eventId).isFalse();
        }
    }
}
