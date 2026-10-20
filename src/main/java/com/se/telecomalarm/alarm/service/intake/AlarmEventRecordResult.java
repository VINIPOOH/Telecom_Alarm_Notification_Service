package com.se.telecomalarm.alarm.service.intake;

import java.util.List;
import com.se.telecomalarm.alarm.service.AlarmEvent;

record AlarmEventRecordResult(AlarmEventRecordOutcome eventOutcome, List<AlarmEvent> resurrectedClearedEvents) {

    static AlarmEventRecordResult accepted(List<AlarmEvent> resurrectedClearedEvents) {
        return new AlarmEventRecordResult(AlarmEventRecordOutcome.ACCEPTED, List.copyOf(resurrectedClearedEvents));
    }

    static AlarmEventRecordResult rejected() {
        return new AlarmEventRecordResult(AlarmEventRecordOutcome.REJECTED, List.of());
    }

    static AlarmEventRecordResult exactRepeat() {
        return new AlarmEventRecordResult(AlarmEventRecordOutcome.EXACT_REPEAT, List.of());
    }
}
