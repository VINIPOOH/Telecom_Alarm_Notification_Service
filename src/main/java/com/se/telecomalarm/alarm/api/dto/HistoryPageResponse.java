package com.se.telecomalarm.alarm.api.dto;

import java.util.List;

/**
 * The body of GET /api/alarms/history: a page of events and the cursor of the next page (API decisions, 16.10.2026).
 * The wrapper exists because the cursor must lie next to the page.
 *
 * @param items the page, in the order of the history: timestamp, then eventId
 * @param next  the opaque string that goes to {@code ?after=} for the next page; {@code null} on the last page
 *              (written as {@code "next": null}, the key is always there)
 */
public record HistoryPageResponse(
        List<HistoryItemResponse> items,
        String next) {
}
