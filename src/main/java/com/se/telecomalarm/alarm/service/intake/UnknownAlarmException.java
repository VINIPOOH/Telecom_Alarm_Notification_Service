package com.se.telecomalarm.alarm.service.intake;

/**
 * A CLEARED for an unknown alarm: the alarm has no accepted RAISED earlier than this CLEARED by the total order.
 * <p>
 * Thrown after the commit, outside the transaction: the event must stay stored as REJECTED (a later-arriving,
 * earlier-timed RAISED may resurrect it), and an exception inside {@code @Transactional} would roll that row back.
 */
public class UnknownAlarmException extends RuntimeException {

    private final String eventId;

    public UnknownAlarmException(String eventId) {
        super("CLEARED without an earlier accepted RAISED of its pair; stored as REJECTED");
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
