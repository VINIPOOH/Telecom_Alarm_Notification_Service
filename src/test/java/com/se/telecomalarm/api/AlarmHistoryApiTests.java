package com.se.telecomalarm.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The D tests of GET /api/alarms/history: D-09 .. D-17.
 * <p>
 * The response is {@code {items, next}}: an item is the full event with {@code status} (no {@code result}, no
 * {@code notification_sent}), only ACCEPTED events, sorted by timestamp and then eventId, two optional filters
 * ({@code elementId}, {@code alarmCode}) combined with AND, {@code limit} 50 by default and 500 at most.
 * <p>
 * The cursor is opaque: the tests never decode {@code next} and never build a cursor. They only take {@code next}
 * from a response and pass it back as {@code after}. The one exception is D-16 "wrong structure", which sends the
 * base64url of a foreign JSON on purpose.
 * <p>
 * Data is created through POST /api/alarms only, so the tests see what a client sees.
 * <p>
 * eventIds that take part in ordering (D-12 .. D-15, D-17) are lower-case letters and digits only, with no hyphen or
 * underscore and no upper case: for such strings the sort of the database (whatever its collation) and the sort of
 * Java ({@code String.compareTo}) agree, so the expected order computed by the tests is the right one.
 */
class AlarmHistoryApiTests extends AlarmStateTestBase {

    private static final String HISTORY = "/api/alarms/history";

    /**
     * Fixed seed: the arrival order of the seeded events looks random but is the same in every run.
     */
    private static final long SEED = 16_10_2026L;

    /**
     * Guard of {@link #readPages}: a server that hands out a cursor forever fails the test instead of hanging it.
     */
    private static final int MAX_PAGES = 50;

    private static final String TARGET = "BTS-100";
    private static final String OTHER = "BTS-200";
    private static final String TARGET_FILTER = "elementId=" + TARGET;

    // ---- D-09: shape and content ---------------------------------------------------------------------------------

    /**
     * One filter at a time: elementId (events of both its alarms), alarmCode (events of both elements).
     */
    static Stream<Arguments> singleFilters() {
        return Stream.of(
                arguments("elementId=BTS-100", List.of("e1", "e2", "e4", "e6")),
                arguments("alarmCode=CELL_DOWN", List.of("e1", "e3", "e4", "e6")));
    }

    // ---- D-10, D-11: filters -------------------------------------------------------------------------------------

    /**
     * Filters together are AND: elementId removes e3 (BTS-200), alarmCode removes e2 (HIGH_CPU), so only the events
     * of the pair (BTS-100, CELL_DOWN) are left. A filter value that nothing has (no such element) is not an error:
     * the page is empty, {@code items: []} and {@code next: null}.
     */
    static Stream<Arguments> combinedFilters() {
        return Stream.of(
                arguments("elementId=BTS-100&alarmCode=CELL_DOWN", List.of("e1", "e4", "e6")),
                arguments("elementId=BTS-999", List.of()));
    }

    /**
     * Every invalid parameter is a separate case. "not-base64" uses only letters of the base64url alphabet, so it
     * may be decoded to bytes that are not JSON; "***" has characters outside the alphabet and cannot be decoded
     * at all. Both are "the cursor does not decode".
     */
    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                arguments("after is not base64", "after=not-base64"),
                arguments("after has characters outside the base64url alphabet", "after=***"),
                arguments("after is the base64url of a JSON of another structure", "after=" + foreignCursor()),
                arguments("limit is 0", "limit=0"),
                arguments("limit is 501", "limit=501"));
    }

    /**
     * Checks the shape of a page: 200, JSON, the body an object with exactly {@code items} and {@code next}, an item
     * with exactly the seven keys of the event (so no result, no notification_sent) and a numeric timestamp
     * (normalised to {@link Long}), {@code next} a non-empty string or null.
     */
    @SuppressWarnings("unchecked")
    private static Page parsePage(ApiResponse response) {
        assertStatus(response, 200);
        assertThat(response.mediaType()).as("Content-Type of %s", response).isEqualTo("application/json");
        Object body = response.json().read("$");
        assertThat(body).as("the body must be a JSON object: %s", response).isInstanceOf(Map.class);
        Map<String, Object> object = (Map<String, Object>) body;
        assertThat(object.keySet()).as("keys of the body (exactly items and next): %s", response)
                .containsExactlyInAnyOrder("items", "next");

        Object items = object.get("items");
        assertThat(items).as("items must be a JSON array: %s", response).isInstanceOf(List.class);
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (Object element : (List<?>) items) {
            assertThat(element).as("an item must be a JSON object: %s", response).isInstanceOf(Map.class);
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) element);
            assertThat(item.keySet()).as("keys of an item (the full event with status, nothing else): %s", response)
                    .containsExactlyInAnyOrder("eventId", "elementId", "alarmCode", "severity", "status", "timestamp", "description");
            assertThat(item.get("timestamp")).as("timestamp must be a JSON number: %s", response).isInstanceOf(Number.class);
            item.put("timestamp", ((Number) item.get("timestamp")).longValue());
            parsed.add(item);
        }

        Object next = object.get("next");
        if (next != null) {
            assertThat(next).as("next must be a string or null: %s", response).isInstanceOf(String.class);
            assertThat((String) next).as("next must not be empty: %s", response).isNotBlank();
        }
        return new Page(response, parsed, (String) next);
    }

    private static List<Page> concat(Page first, List<Page> rest) {
        List<Page> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /**
     * The check of D-13 .. D-15, in four steps, each with its own message so a failure says what kind it is:
     * <ol>
     *   <li>no eventId on two pages (no duplicates);</li>
     *   <li>every expected eventId is on some page (no gaps);</li>
     *   <li>no eventId that is not expected (nothing extra);</li>
     *   <li>the pages together are in the expected order, and the last page has {@code next = null}.</li>
     * </ol>
     */
    private static void assertPagesCover(List<Page> pages, List<String> expectedInOrder) {
        List<String> seen = pages.stream().flatMap(page -> page.eventIds().stream()).toList();

        assertThat(seen).as("1. no event on two pages (duplicates)").doesNotHaveDuplicates();

        List<String> missing = new ArrayList<>(expectedInOrder);
        missing.removeAll(seen);
        assertThat(missing).as("2. events that no page returned (gaps)").isEmpty();

        List<String> unexpected = new ArrayList<>(seen);
        unexpected.removeAll(expectedInOrder);
        assertThat(unexpected).as("3. events that no page should return").isEmpty();

        assertThat(seen).as("4. the events of all pages together, in the order of the history").containsExactlyElementsOf(expectedInOrder);
        assertThat(pages.get(pages.size() - 1).next()).as("4. next of the last page").isNull();
    }

    // ---- D-12: order ---------------------------------------------------------------------------------------------

    /**
     * The eventIds in the order of the history: timestamp, then eventId as a string.
     */
    private static List<String> inHistoryOrder(List<TestEvent> events) {
        return events.stream()
                .sorted(Comparator.comparingLong(TestEvent::timestamp).thenComparing(TestEvent::eventId))
                .map(TestEvent::eventId)
                .toList();
    }

    // ---- D-13 .. D-15: keyset ------------------------------------------------------------------------------------

    /**
     * The non-empty parts joined with '&'.
     */
    private static String query(String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isEmpty())
                .collect(Collectors.joining("&"));
    }

    /**
     * A base64url string (no padding) that decodes to JSON, but not to a cursor: {@code {"x":1}}.
     */
    private static String foreignCursor() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("D-09 /history: only accepted events as {items, next: null}; an item is the full event with status, without result and notification_sent")
    void history_listsOnlyAcceptedEvents_asFullEventsWithStatus() {
        TestEvent raised = raised(at(0), "CRITICAL", "e1").withDescription("Cell is not responding");
        TestEvent rejected = cleared(at(3), "e2").forPair("BTS-100", "HIGH_CPU"); // this pair was never raised: 409, REJECTED
        TestEvent cleared = cleared(at(5), "e3");
        postAccepted(raised);
        postRejected(rejected);
        postAccepted(cleared);
        assertThat(storedResults()).as("precondition: e2 is stored, as REJECTED")
                .isEqualTo(Map.of("e1", ACCEPTED, "e2", REJECTED, "e3", ACCEPTED));

        Page page = getPage("");

        assertThat(page.items()).as("items of %s", page.response())
                .containsExactly(raised.asHistoryItem(), cleared.asHistoryItem());
        assertThat(page.next()).as("next of %s", page.response()).isNull();
    }

    /**
     * The history of D-10 and D-11 (accepted events e1 .. e6 and one rejected e7). Only the pairs matter for the
     * filters (elementId, alarmCode); the other columns are just valid values:
     * <pre>
     *   e1  R  10:00  MAJOR     BTS-100 CELL_DOWN
     *   e2  R  10:01  CRITICAL  BTS-100 HIGH_CPU
     *   e3  R  10:02  CRITICAL  BTS-200 CELL_DOWN
     *   e4  C  10:03  (MAJOR)   BTS-100 CELL_DOWN
     *   e5  R  10:04  WARNING   BTS-200 HIGH_CPU
     *   e6  R  10:05  WARNING   BTS-100 CELL_DOWN
     *   e7  C  10:03  (MAJOR)   BTS-300 CELL_DOWN   rejected: this pair was never raised
     * </pre>
     * e7 has the alarmCode CELL_DOWN, so a filtered query that forgets "only accepted" returns it and fails.
     */
    private void seedFilterHistory() {
        postAccepted(
                raised(at(0), "MAJOR", "e1"),
                raised(at(1), "CRITICAL", "e2").forPair("BTS-100", "HIGH_CPU"),
                raised(at(2), "CRITICAL", "e3").forPair("BTS-200", "CELL_DOWN"),
                cleared(at(3), "e4"),
                raised(at(4), "WARNING", "e5").forPair("BTS-200", "HIGH_CPU"),
                raised(at(5), "WARNING", "e6"));
        postRejected(cleared(at(3), "e7").forPair("BTS-300", "CELL_DOWN"));
    }

    // ---- D-16: invalid parameters ----------------------------------------------------------------------------------

    @ParameterizedTest(name = "?{0} -> {1}")
    @MethodSource("singleFilters")
    @DisplayName("D-10 /history: each filter alone (elementId, alarmCode) returns only the matching accepted events")
    void filter_returnsOnlyTheMatchingAcceptedEvents(String filter, List<String> expectedEventIds) {
        seedFilterHistory();

        Page page = getPage(filter);

        assertThat(page.eventIds()).as("events for ?%s: %s", filter, page.response())
                .containsExactlyElementsOf(expectedEventIds);
        assertThat(page.next()).as("next for ?%s: %s", filter, page.response()).isNull();
    }

    @ParameterizedTest(name = "?{0} -> {1}")
    @MethodSource("combinedFilters")
    @DisplayName("D-11 /history: filters together are combined with AND; an element that does not exist gives an empty page, not an error")
    void filtersTogether_returnOnlyEventsThatMatchAll_andUnknownValueGivesEmptyPage(String filter, List<String> expectedEventIds) {
        seedFilterHistory();

        Page page = getPage(filter);

        assertThat(page.eventIds()).as("events for ?%s: %s", filter, page.response())
                .containsExactlyElementsOf(expectedEventIds);
        assertThat(page.next()).as("next for ?%s: %s", filter, page.response()).isNull();
    }

    // ---- D-17: limit -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-12 /history: sorted by timestamp, equal timestamps by eventId as a string")
    void history_isSortedByTimestamp_thenByEventId() {
        // The arrival order differs from the expected order in both keys. Four events share the time 10:01: their
        // order is the order of the eventIds as strings, so "e10" comes before "e9" (not a numeric comparison).
        postAccepted(
                raised(at(2), "MAJOR", "ed"),
                raised(at(1), "MAJOR", "eb"),
                raised(at(0), "MAJOR", "ec"),
                raised(at(1), "MAJOR", "e9"),
                raised(at(1), "MAJOR", "ea"),
                raised(at(1), "MAJOR", "e10"));

        Page page = getPage("");

        assertThat(page.eventIds()).as("order of %s", page.response())
                .containsExactly("ec", "e10", "e9", "ea", "eb", "ed");
    }

    // ---- reading pages ---------------------------------------------------------------------------------------------

    /**
     * The history of D-13 .. D-15. Returns the 25 events of BTS-100 that the tests expect on the pages (the target):
     * <ul>
     *   <li>h01 .. h25; groups of three share a timestamp (h01-h03 at 10:00, h04-h06 at 10:01, ...), so with a page of
     *       10 the border of the first page falls between h10 and h11 of the group h10-h12, and the border of the
     *       second between h20 and h21 of the group h19-h21: a cursor that remembers only the timestamp would skip
     *       h11, h12 and h21;</li>
     *   <li>5 events of BTS-200 (n1 .. n5) with times between those of the target: the filter {@code elementId=BTS-100}
     *       must hold on every page, not only on the first;</li>
     *   <li>1 rejected CLEARED of BTS-100 (x1): no page may return it.</li>
     * </ul>
     * The target and the BTS-200 events are sent in a shuffled order (fixed seed), so neither the insertion order
     * nor the arrival order is the sort order.
     */
    private List<TestEvent> seedPagedHistory() {
        List<TestEvent> target = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            target.add(raised(at(i / 3), "MAJOR", String.format(Locale.ROOT, "h%02d", i + 1)));
        }
        List<TestEvent> arrival = new ArrayList<>(target);
        arrival.add(raised(at(0), "MAJOR", "n1").forPair(OTHER, "CELL_DOWN"));
        arrival.add(raised(at(1), "MAJOR", "n2").forPair(OTHER, "CELL_DOWN"));
        arrival.add(raised(at(3), "CRITICAL", "n3").forPair(OTHER, "HIGH_CPU"));
        arrival.add(raised(at(5), "INFO", "n4").forPair(OTHER, "HIGH_CPU"));
        arrival.add(raised(at(8), "WARNING", "n5").forPair(OTHER, "CELL_DOWN"));
        Collections.shuffle(arrival, new Random(SEED));
        postAccepted(arrival.toArray(new TestEvent[0]));
        postRejected(cleared(at(2), "x1").forPair(TARGET, "HIGH_CPU"));
        return target;
    }

    @Test
    @DisplayName("D-13 keyset: following next through 25 events with limit 10 gives pages of 10, 10 and 5, every event once, in order; the last next is null")
    void followingTheCursor_visitsEveryEventExactlyOnce_inOrder() {
        List<TestEvent> events = seedPagedHistory();

        List<Page> pages = readPages(TARGET_FILTER, 10, null);

        assertThat(pages.stream().map(page -> page.items().size()).toList()).as("sizes of the pages").containsExactly(10, 10, 5);
        assertPagesCover(pages, inHistoryOrder(events));
    }

    @Test
    @DisplayName("D-14 keyset: an event with the latest time inserted between two page requests is not duplicated and is the last event on the last page")
    void eventInsertedAtTheEndBetweenPages_isNotDuplicated_andIsOnTheLastPage() {
        List<TestEvent> events = seedPagedHistory();
        Page first = getPage(query(TARGET_FILTER, "limit=10"));
        assertThat(first.next()).as("next of the first page: %s", first.response()).isNotNull();

        // Later than every event of the history (the latest of them is at(8)).
        TestEvent newest = raised(at(20), "MAJOR", "newest");
        postAccepted(newest);

        List<Page> rest = readPages(TARGET_FILTER, 10, first.next());

        List<Page> all = concat(first, rest);
        List<TestEvent> expected = new ArrayList<>(events);
        expected.add(newest);
        assertPagesCover(all, inHistoryOrder(expected));
        assertThat(all.get(all.size() - 1).eventIds()).as("the last page: %s", all.get(all.size() - 1).response())
                .endsWith("newest");
    }

    @Test
    @DisplayName("D-15 keyset (documented limit): an event that arrives late, with an earlier time, is not on the pages after the cursor, but a new traversal has it")
    void lateEventInsertedBetweenPages_isNotSeenByTheReader_butIsSeenByANewTraversal() {
        List<TestEvent> events = seedPagedHistory();
        Page first = getPage(query(TARGET_FILTER, "limit=10"));
        assertThat(first.next()).as("next of the first page: %s", first.response()).isNotNull();

        // Earlier than every event of the history (the earliest is at(0)); its eventId sorts after "h25", so a cursor
        // that looked at the eventId alone would show it on a later page.
        TestEvent late = raised(at(-5), "MAJOR", "late");
        postAccepted(late);

        List<Page> rest = readPages(TARGET_FILTER, 10, first.next());

        List<String> restIds = rest.stream().flatMap(page -> page.eventIds().stream()).toList();
        assertThat(restIds).as("pages after the cursor: the late event is before the cursor").doesNotContain("late");
        assertPagesCover(concat(first, rest), inHistoryOrder(events));

        List<TestEvent> withLate = new ArrayList<>(events);
        withLate.add(late);
        assertPagesCover(readPages(TARGET_FILTER, 10, null), inHistoryOrder(withLate));
    }

    @ParameterizedTest(name = "{0}: ?{1}")
    @MethodSource("invalidParameters")
    @DisplayName("D-16 /history: an invalid parameter is a 400 with a problem+json body (O1-O3)")
    void invalidParameter_isRejectedWithProblemDetail(String reason, String query) {
        // The history is not empty, so the 400 is not a side effect of an empty answer.
        postAccepted(raised(at(0), "MAJOR", "e1"), raised(at(1), "CRITICAL", "e2"));

        ApiResponse response = get(HISTORY + "?" + query);

        ProblemAssertions.assertProblem(response, 400);
    }

    @Test
    @DisplayName("D-17 /history: without limit a page has 50 events and a non-empty next; limit=500 is accepted")
    void defaultLimitIs50_andLimit500IsAccepted() {
        List<TestEvent> events = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            events.add(raised(t0 + i * SECOND, "MAJOR", String.format(Locale.ROOT, "h%02d", i + 1)));
        }
        postAccepted(events.toArray(new TestEvent[0]));
        List<String> expected = inHistoryOrder(events);

        Page defaultPage = getPage("");
        assertThat(defaultPage.eventIds()).as("events without limit: %s", defaultPage.response())
                .containsExactlyElementsOf(expected.subList(0, 50));
        assertThat(defaultPage.next()).as("next without limit (10 events are left): %s", defaultPage.response()).isNotNull();

        Page maxPage = getPage("limit=500");
        assertThat(maxPage.eventIds()).as("events with limit=500: %s", maxPage.response())
                .containsExactlyElementsOf(expected);
        assertThat(maxPage.next()).as("next with limit=500 (all 60 fit): %s", maxPage.response()).isNull();
    }

    /**
     * GET /history with this query (no leading '?'); the response must be a valid page.
     */
    private Page getPage(String query) {
        return parsePage(get(query.isEmpty() ? HISTORY : HISTORY + "?" + query));
    }

    // ---- query strings ---------------------------------------------------------------------------------------------

    /**
     * Reads pages one after another, each with the same filters and limit, until a page has {@code next = null}:
     * the first request has no {@code after} (or the given cursor), every next one has {@code after} = the
     * {@code next} of the previous response, passed as it came.
     */
    private List<Page> readPages(String filters, int limit, String after) {
        List<Page> pages = new ArrayList<>();
        String cursor = after;
        do {
            String afterParam = cursor == null ? null : "after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            Page page = getPage(query(filters, "limit=" + limit, afterParam));
            pages.add(page);
            cursor = page.next();
            assertThat(pages.size()).as("the cursor must lead to the last page (next = null) in at most %d pages", MAX_PAGES)
                    .isLessThanOrEqualTo(MAX_PAGES);
        } while (cursor != null);
        return pages;
    }

    /**
     * One page of GET /api/alarms/history: the items in the order sent, and the cursor of the next page or null.
     */
    private record Page(ApiResponse response, List<Map<String, Object>> items, String next) {

        List<String> eventIds() {
            return items.stream().map(item -> (String) item.get("eventId")).toList();
        }
    }
}
