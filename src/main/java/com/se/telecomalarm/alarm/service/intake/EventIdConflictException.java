package com.se.telecomalarm.alarm.service.intake;

/**
 * The eventId is already taken and the request is not an exact repeat of an accepted event: the stored event
 * has other data, or another alarm, or it was rejected.
 */
public class EventIdConflictException extends RuntimeException {

    private final String eventId;

    public EventIdConflictException(String eventId) {
        super("eventId was received earlier and this request is not an exact repeat of an accepted event");
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
