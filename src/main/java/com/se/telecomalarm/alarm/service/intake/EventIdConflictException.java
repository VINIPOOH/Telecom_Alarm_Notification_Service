package com.se.telecomalarm.alarm.service.intake;

/**
 * The eventId is already taken and the request is not an exact repeat of an accepted event: the stored event
 * has other data, or another alarm, or it was rejected.
 * <p>
 * The message is for the log only; it carries no stored values.
 */
public class EventIdConflictException extends RuntimeException {

    private final String eventId;

    public EventIdConflictException(String eventId) {
        super("eventId was received earlier and this request is not an exact repeat of an accepted event");
        this.eventId = eventId;
    }

    /**
     * The id the client itself sent.
     */
    public String eventId() {
        return eventId;
    }
}
