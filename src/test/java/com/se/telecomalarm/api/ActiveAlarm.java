package com.se.telecomalarm.api;

/**
 * One record of GET /api/alarms/active (and /elements/{id}), SPEC section 4: the last accepted event of an active
 * pair. Tests compare whole records, so a wrong field (for example the severity of one event and the description
 * of another) is caught, not only the presence of the pair.
 */
record ActiveAlarm(
        String elementId,
        String alarmCode,
        String severity,
        String description,
        String eventId,
        long timestamp) {

    /**
     * What /active must show for a pair whose last accepted event is this RAISED.
     */
    static ActiveAlarm of(TestEvent raised) {
        if (!raised.isRaised()) {
            throw new IllegalArgumentException("Only a RAISED event can be the active record of a pair: " + raised);
        }
        return new ActiveAlarm(
                raised.elementId(), raised.alarmCode(), raised.severity(), raised.description(),
                raised.eventId(), raised.timestamp());
    }
}
