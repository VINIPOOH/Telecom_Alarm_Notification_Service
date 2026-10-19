package com.se.telecomalarm.alarm.service.history;

public interface AlarmHistoryReaderService {

    /**
     * One page of the history: only ACCEPTED events, in the order (timestamp, eventId).
     *
     * @param elementId filter, equality; null if there is none
     * @param alarmCode filter, equality; null if there is none
     * @param after     read after this position; null for the first page
     * @param limit     page size, at least 1 (checked by the caller)
     */
    HistoryPage readPage(String elementId, String alarmCode, HistoryPosition after, int limit);
}
