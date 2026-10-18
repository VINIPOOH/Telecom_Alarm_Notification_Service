package com.se.telecomalarm.alarm.service.intake;

import java.util.List;
import com.se.telecomalarm.alarm.service.AlarmEvent;

/**
 * The result of {@link AlarmEventRecorderService#record}, read by {@link DefaultAlarmEventService} after the commit.
 *
 * @param outcome     what happened to the event itself
 * @param resurrected the CLEARED events this RAISED resurrected, in notification order; empty otherwise
 */
record Recorded(RecordedOutcome outcome, List<AlarmEvent> resurrected) {

    static Recorded accepted(List<AlarmEvent> resurrected) {
        return new Recorded(RecordedOutcome.ACCEPTED, List.copyOf(resurrected));
    }

    static Recorded rejected() {
        return new Recorded(RecordedOutcome.REJECTED, List.of());
    }

    static Recorded exactRepeat() {
        return new Recorded(RecordedOutcome.EXACT_REPEAT, List.of());
    }
}
