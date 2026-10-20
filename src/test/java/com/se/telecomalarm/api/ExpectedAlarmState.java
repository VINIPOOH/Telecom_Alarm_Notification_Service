package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The "right answer" of the state tests, computed by the tests themselves from the state rules B1-B6, without any
 * application code.
 * <p>
 * It is deliberately NOT the algorithm the application runs. The application decides event by event, in arrival
 * order, under a per-pair lock: reject a CLEARED, later resurrect it, replace the active record. Here the final
 * state is written as a closed formula over the whole set of events, which needs no order, no lock and no
 * resurrection step:
 * <ul>
 *   <li>a RAISED is always accepted (an outdated RAISED is stored and accepted too);</li>
 *   <li>a CLEARED ends up accepted if and only if the set has a RAISED of the same pair earlier than it by the
 *       total order. If that RAISED arrived first, the CLEARED is accepted at once; if it arrived later, it
 *       resurrects the CLEARED. Either way the end is the same;</li>
 *   <li>a pair is active if and only if its latest accepted event by the total order is a RAISED; the active record
 *       is that event.</li>
 * </ul>
 * If the application and this formula disagree, one of them breaks the rules, whatever the arrival order was.
 * Only {@link #sequentialStatuses} looks at an order: the HTTP answer to each request does depend on what has
 * already arrived.
 */
final class ExpectedAlarmState {

    static final String ACCEPTED = "ACCEPTED";
    static final String REJECTED = "REJECTED";
    /**
     * The total order of events of one pair (rules B1, B2), ascending: the later event compares greater.
     * <ol>
     *   <li>greater timestamp is later;</li>
     *   <li>equal timestamps: RAISED is later than CLEARED;</li>
     *   <li>both RAISED: higher severity is later;</li>
     *   <li>otherwise the greater eventId as a string is later.</li>
     * </ol>
     */
    static final Comparator<TestEvent> TOTAL_ORDER = (a, b) -> {
        int byTime = Long.compare(a.timestamp(), b.timestamp());
        if (byTime != 0) {
            return byTime;
        }
        if (a.isRaised() != b.isRaised()) {
            return a.isRaised() ? 1 : -1;
        }
        if (a.isRaised()) {
            int bySeverity = Integer.compare(severityRank(a), severityRank(b));
            if (bySeverity != 0) {
                return bySeverity;
            }
        }
        return a.eventId().compareTo(b.eventId());
    };
    /**
     * Index = rank: CRITICAL > MAJOR > WARNING > INFO.
     */
    private static final List<String> SEVERITIES_ASCENDING = List.of("INFO", "WARNING", "MAJOR", "CRITICAL");

    private ExpectedAlarmState() {
    }

    /**
     * eventId -> ACCEPTED / REJECTED once every event of the set has been processed, in any arrival order.
     */
    static Map<String, String> finalResults(Collection<TestEvent> events) {
        Map<String, String> results = new TreeMap<>();
        for (TestEvent event : events) {
            results.put(event.eventId(), isFinallyAccepted(event, events) ? ACCEPTED : REJECTED);
        }
        return results;
    }

    /**
     * GET /api/alarms/active once every event of the set has been processed, sorted by elementId, then alarmCode.
     */
    static List<ActiveAlarm> finalActive(Collection<TestEvent> events) {
        Map<String, List<TestEvent>> byPair = events.stream()
                .collect(Collectors.groupingBy(TestEvent::pairKey, LinkedHashMap::new, Collectors.toList()));
        List<ActiveAlarm> active = new ArrayList<>();
        for (List<TestEvent> pairEvents : byPair.values()) {
            pairEvents.stream()
                    .filter(event -> isFinallyAccepted(event, pairEvents))
                    .max(TOTAL_ORDER)
                    .filter(TestEvent::isRaised)
                    .map(ActiveAlarm::of)
                    .ifPresent(active::add);
        }
        active.sort(Comparator.comparing(ActiveAlarm::elementId).thenComparing(ActiveAlarm::alarmCode));
        return active;
    }

    /**
     * The HTTP status of each request when the events are sent one after another in this order (no eventId twice):
     * a RAISED gets 201; a CLEARED gets 201 if a RAISED of its pair that is earlier than it by the total order has
     * already arrived (every RAISED that has arrived is accepted), otherwise 409.
     */
    static List<Integer> sequentialStatuses(List<TestEvent> arrivalOrder) {
        List<TestEvent> arrivedRaised = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        for (TestEvent event : arrivalOrder) {
            if (event.isRaised()) {
                arrivedRaised.add(event);
                statuses.add(201);
            } else {
                statuses.add(hasEarlierRaised(event, arrivedRaised) ? 201 : 409);
            }
        }
        return statuses;
    }

    private static boolean isFinallyAccepted(TestEvent event, Collection<TestEvent> events) {
        return event.isRaised() || hasEarlierRaised(event, events);
    }

    private static boolean hasEarlierRaised(TestEvent cleared, Collection<TestEvent> candidates) {
        return candidates.stream().anyMatch(other -> other.isRaised()
                && other.pairKey().equals(cleared.pairKey())
                && TOTAL_ORDER.compare(other, cleared) < 0);
    }

    private static int severityRank(TestEvent event) {
        int rank = SEVERITIES_ASCENDING.indexOf(event.severity());
        if (rank < 0) {
            throw new IllegalArgumentException("Unknown severity in test data: " + event);
        }
        return rank;
    }
}
