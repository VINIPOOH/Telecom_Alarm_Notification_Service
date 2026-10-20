package com.se.telecomalarm.alarm.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;
import com.se.telecomalarm.alarm.api.dto.ActiveAlarmResponse;
import com.se.telecomalarm.alarm.api.dto.HistoryItemResponse;
import com.se.telecomalarm.alarm.api.dto.HistoryPageResponse;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.active.ActiveAlarmReaderService;
import com.se.telecomalarm.alarm.service.history.AlarmHistoryReaderService;
import com.se.telecomalarm.alarm.service.history.HistoryPage;
import com.se.telecomalarm.alarm.service.history.HistoryCursor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
public class AlarmReadController {

    static final String DEFAULT_LIMIT = "50";
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 500;
    private static final String LIMIT_MESSAGE = "must be between " + MIN_LIMIT + " and " + MAX_LIMIT;

    private final ActiveAlarmReaderService activeAlarms;
    private final AlarmHistoryReaderService history;
    private final HistoryCursorCodec historyCursorCodecService;

    AlarmReadController(ActiveAlarmReaderService activeAlarms, AlarmHistoryReaderService history,
                        HistoryCursorCodec historyCursorCodecService) {
        this.activeAlarms = activeAlarms;
        this.history = history;
        this.historyCursorCodecService = historyCursorCodecService;
    }

    private static ActiveAlarmResponse toActiveAlarmResponse(AlarmEvent event) {
        return new ActiveAlarmResponse(event.elementId(), event.alarmCode(), event.severity(), event.description(),
                                       event.eventId(), event.timestamp());
    }

    private static HistoryItemResponse toHistoryItemResponse(AlarmEvent event) {
        return new HistoryItemResponse(event.eventId(), event.elementId(), event.alarmCode(), event.severity(),
                                       event.status(), event.timestamp(), event.description());
    }

    @GetMapping("/active")
    public List<ActiveAlarmResponse> active() {
        return activeAlarms.findAllActiveAlarms().stream().map(AlarmReadController::toActiveAlarmResponse).toList();
    }

    /**
     * The active alarms of one element, in the same form as /active, sorted by alarmCode. An element without active gives 200 and an empty list.
     */
    @GetMapping("/elements/{elementId}")
    public List<ActiveAlarmResponse> element(@PathVariable("elementId") String elementId) {
        return activeAlarms.findActiveAlarmsByElement(elementId).stream().map(AlarmReadController::toActiveAlarmResponse).toList();
    }

    /**
     * An object {"INFO": n, "WARNING": n, "MAJOR": n, "CRITICAL": n}: all four keys, zeros included.
     */
    @GetMapping("/statistics")
    public Map<AlarmSeverity, Long> statistics() {
        return activeAlarms.countAlarmsBySeverity();
    }

    @GetMapping("/history")
    public HistoryPageResponse history(
            @RequestParam(name = "elementId", required = false) String elementId,
            @RequestParam(name = "alarmCode", required = false) String alarmCode,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT)
            @Min(value = MIN_LIMIT, message = LIMIT_MESSAGE) @Max(value = MAX_LIMIT, message = LIMIT_MESSAGE) int limit) {
        HistoryCursor position = after == null ? null : historyCursorCodecService.decode(after);

        HistoryPage page = history.readPage(elementId, alarmCode, position, limit);

        List<AlarmEvent> items = page.items();
        String next = page.hasNextPage()
                      ? historyCursorCodecService.encode(new HistoryCursor(items.getLast().timestamp(), items.getLast().eventId()))
                      : null;
        return new HistoryPageResponse(items.stream().map(AlarmReadController::toHistoryItemResponse).toList(), next);
    }
}
