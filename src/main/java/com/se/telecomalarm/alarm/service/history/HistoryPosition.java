package com.se.telecomalarm.alarm.service.history;

/**
 * The last row a client has seen. The next page is everything after it in the order of the
 * history, (timestamp, eventId)
 *
 * @param timestamp event_timestamp of the row, epoch milliseconds
 * @param eventId   event_id of the row (the tie-breaker of equal timestamps)
 */
public record HistoryPosition(
        long timestamp,
        String eventId) {
}
