package com.se.telecomalarm.alarm.service.intake;

import java.util.Comparator;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import org.springframework.stereotype.Service;

/**
 * The total order of the events of one alarm: which of two events counts as later.
 * <ol>
 *   <li>the event with the greater {@code timestamp} is later;</li>
 *   <li>equal timestamps: a RAISED is later than a CLEARED (a missed alarm is worse than a false one);</li>
 *   <li>equal timestamps, both RAISED: the event with the greater severity is later (INFO < WARNING < MAJOR <
 *       CRITICAL);</li>
 *   <li>otherwise: the event with the greater {@code eventId} as a string is later. No domain meaning: it only makes
 *       the result the same for every arrival order.</li>
 * </ol>
 * Two different events never compare as equal: eventIds are unique, so rule 4 always decides. Rule 4 is
 * {@link String#compareTo} (UTF-16 code units), not {@code ORDER BY event_id}, which follows the database collation;
 * that is why the last tie-break is never done in SQL.
 */
@Service
class AlarmEventComparator implements Comparator<AlarmEvent> {

    private static boolean isRaised(AlarmEvent event) {
        return event.status() == AlarmStatus.RAISED;
    }

    /**
     * Ascending: {@code compare(a, b) > 0} means a is later than b.
     */
    @Override
    public int compare(AlarmEvent a, AlarmEvent b) {
        int byTime = Long.compare(a.timestamp(), b.timestamp());
        if (byTime != 0) {
            return byTime;
        }
        int byStatus = Boolean.compare(isRaised(a), isRaised(b)); // false < true: RAISED is later than CLEARED
        if (byStatus != 0) {
            return byStatus;
        }
        if (isRaised(a)) {
            int bySeverity = a.severity().compareTo(b.severity());
            if (bySeverity != 0) {
                return bySeverity;
            }
        }
        return a.eventId().compareTo(b.eventId());
    }
}
