package com.se.telecomalarm.alarm.service.history;

import java.util.List;
import com.se.telecomalarm.alarm.service.AlarmEvent;

/**
 * @param items   the page, in the order of the history: timestamp, then eventId
 * @param hasNext true if there are more events after the last item
 */
public record HistoryPage(
        List<AlarmEvent> items,
        boolean hasNext) {
}
