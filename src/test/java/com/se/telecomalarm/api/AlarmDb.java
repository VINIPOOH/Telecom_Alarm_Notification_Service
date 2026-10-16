package com.se.telecomalarm.api;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Direct SQL access to the service's tables, bypassing the application code.
 */
final class AlarmDb {

    private final JdbcTemplate jdbc;

    AlarmDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Empties both tables. Rows only: tables, types and the Flyway journal stay.
     */
    void truncate() {
        jdbc.execute("TRUNCATE active_alarms, alarm_events");
    }

    List<HistoryRow> history() {
        return jdbc.query("""
                                  SELECT event_id, element_id, alarm_code, severity, status, event_timestamp,
                                  	   description, acceptance, notification_sent
                                  FROM alarm_events
                                  ORDER BY event_id
                                  """,
                          (rs, rowNum) -> new HistoryRow(
                                  rs.getString("event_id"),
                                  rs.getString("element_id"),
                                  rs.getString("alarm_code"),
                                  rs.getString("severity"),
                                  rs.getString("status"),
                                  rs.getLong("event_timestamp"),
                                  rs.getString("description"),
                                  rs.getString("acceptance"),
                                  rs.getBoolean("notification_sent")));
    }

    /**
     * The {@code notification_sent} flag of one stored event; fails if the event is not stored.
     */
    boolean notificationSent(String eventId) {
        Boolean sent = jdbc.queryForObject(
                "SELECT notification_sent FROM alarm_events WHERE event_id = ?", Boolean.class, eventId);
        return Boolean.TRUE.equals(sent);
    }

    List<String> activeEventIds() {
        return jdbc.queryForList("SELECT event_id FROM active_alarms ORDER BY event_id", String.class);
    }

    Snapshot snapshot() {
        return new Snapshot(history(), activeEventIds());
    }

    /**
     * One row of alarm_events; enum columns are read as their text labels.
     */
    record HistoryRow(
            String eventId,
            String elementId,
            String alarmCode,
            String severity,
            String status,
            long timestamp,
            String description,
            String acceptance,
            boolean notificationSent) {
    }

    /**
     * Everything the service stores, for "nothing changed" comparisons.
     */
    record Snapshot(List<HistoryRow> history, List<String> activeEventIds) {
    }
}
