package com.se.telecomalarm.alarm.service.history;

import java.util.List;
import com.se.telecomalarm.alarm.service.AlarmEvent;

public record HistoryPage(
        List<AlarmEvent> items,
        boolean hasNextPage) {
}
