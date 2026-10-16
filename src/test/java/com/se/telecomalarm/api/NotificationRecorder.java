package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

/**
 * A WireMock extension that writes down every request the receiver gets, in the order it got them.
 * <p>
 * Why it exists: the WireMock request journal does not document any order of its entries, and the tests of
 * section E check the order of arrival (E-02, E-10, E-11). Sorting the journal by its logged time would also be
 * wrong: requests handled by different Jetty threads may log times out of order. So the order is fixed here, at the
 * moment each request arrives.
 * <p>
 * How it works, step by step:
 * <ol>
 *   <li>WireMock calls every {@link ServeEventListener} that {@linkplain #applyGlobally() applies globally} at four
 *       points of handling a stub request. {@link #beforeMatch} is the first one: the request has been read, it is
 *       not yet matched to a stub, and no delay or fault of the stub has started (the delay is applied later, when the
 *       response is written). So "before match" is "the moment the request arrived".</li>
 *   <li>Several Jetty threads may call {@link #beforeMatch} at the same time. Each call takes the lock of the list,
 *       reads the clock and appends one entry while holding it, so the list order, the sequence numbers and the
 *       times all agree.</li>
 *   <li>Admin calls of the tests (stubbing, verification) go through another handler and are not recorded.</li>
 * </ol>
 * WireMock creates the instance itself from the class named in {@code @ConfigureWireMock(extensions = ...)} (public
 * class, public no-argument constructor), so the test cannot hold a reference to it: the list is static. That is
 * safe here because the tests run one after another (no parallel JUnit execution in this project), the list is
 * cleared before every test, and after every test the application's queue is empty, so no request of an earlier
 * test can arrive late.
 */
public final class NotificationRecorder implements ServeEventListener {

    private static final List<ReceivedNotification> RECEIVED = new ArrayList<>();

    /**
     * Every request received since the last {@link #clear()}, in the order of arrival.
     */
    static List<ReceivedNotification> received() {
        synchronized (RECEIVED) {
            return List.copyOf(RECEIVED);
        }
    }

    static void clear() {
        synchronized (RECEIVED) {
            RECEIVED.clear();
        }
    }

    /**
     * A string field of a JSON body, or null if the body is not JSON or has no such string field.
     */
    private static String textField(String body, String path) {
        try {
            Object value = JsonPath.parse(body).read(path);
            return value instanceof String text ? text : null;
        } catch (RuntimeException notJsonOrNoField) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "notification-recorder";
    }

    @Override
    public void beforeMatch(ServeEvent serveEvent, Parameters parameters) {
        LoggedRequest request = serveEvent.getRequest();
        String body = request.getBodyAsString();
        String method = String.valueOf(request.getMethod());
        String url = request.getUrl();
        String contentType = request.getHeader("Content-Type");
        String eventId = textField(body, "$.eventId");
        String elementId = textField(body, "$.elementId");
        synchronized (RECEIVED) {
            RECEIVED.add(new ReceivedNotification(
                    RECEIVED.size() + 1, System.nanoTime(), method, url, contentType, body, eventId, elementId));
        }
    }

    /**
     * One request as the receiver got it.
     */
    public record ReceivedNotification(
            int sequence,
            long receivedAtNanos,
            String method,
            String url,
            String contentType,
            String body,
            String eventId,
            String elementId) {

        /**
         * The body as a JSON object with exactly the keys that were sent; the timestamp is a {@link Long}, as in
         * {@link TestEvent#asHistoryItem()}.
         */
        public Map<String, Object> jsonBody() {
            Object parsed = JsonPath.parse(body).read("$");
            if (!(parsed instanceof Map<?, ?> object)) {
                throw new AssertionError("The notification body is not a JSON object: " + body);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach((key, value) -> result.put(
                    (String) key,
                    "timestamp".equals(key) && value instanceof Number number ? (Object) number.longValue() : value));
            return result;
        }

        @Override
        public String toString() {
            return "#" + sequence + " " + method + " " + url + " " + body;
        }
    }
}
