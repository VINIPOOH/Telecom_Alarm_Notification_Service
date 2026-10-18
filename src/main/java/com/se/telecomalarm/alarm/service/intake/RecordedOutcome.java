package com.se.telecomalarm.alarm.service.intake;

/**
 * What {@link AlarmEventRecorderService#record} did with the event itself.
 */
enum RecordedOutcome {
    /**
     * Stored as ACCEPTED.
     */
    ACCEPTED,
    /**
     * Stored as REJECTED; already committed.
     */
    REJECTED,
    /**
     * An exact repeat of an accepted event; nothing written.
     */
    EXACT_REPEAT
}
