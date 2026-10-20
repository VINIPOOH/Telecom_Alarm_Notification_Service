package com.se.telecomalarm.alarm.service.history;

public record HistoryCursor(
        long eventTimestampMillis,
        String tieBreakerEventId) {
}
