package com.se.telecomalarm.alarm.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;
import com.se.telecomalarm.alarm.api.ApiExceptionHandler;
import com.se.telecomalarm.alarm.api.dto.ActiveAlarmResponse;
import com.se.telecomalarm.alarm.api.dto.HistoryItemResponse;
import com.se.telecomalarm.alarm.api.dto.HistoryPageResponse;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.active.ActiveAlarmReaderService;
import com.se.telecomalarm.alarm.service.history.AlarmHistoryReaderService;
import com.se.telecomalarm.alarm.service.history.HistoryPage;
import com.se.telecomalarm.alarm.service.history.HistoryPosition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of the API (SPEC section 4), next to the intake in {@link AlarmController}:
 * GET /active, /elements/{elementId}, /statistics and /history. Formats: API decisions, "Форматы ответов" (16.10.2026).
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmQueryController {

    static final String DEFAULT_LIMIT = "50";
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 500;
    private static final String LIMIT_MESSAGE = "must be between " + MIN_LIMIT + " and " + MAX_LIMIT;

    private final ActiveAlarmReaderService activeAlarms;
    private final AlarmHistoryReaderService history;
    private final HistoryCursorCodec cursors;

    AlarmQueryController(ActiveAlarmReaderService activeAlarms, AlarmHistoryReaderService history,
                         HistoryCursorCodec historyCursorCodecService) {
        this.activeAlarms = activeAlarms;
        this.history = history;
        this.cursors = historyCursorCodecService;
    }

    /**
     * The record of GET /api/alarms/active: the six fields of the event, no status (every active alarm is RAISED).
     */
    private static ActiveAlarmResponse toActiveAlarmResponse(AlarmEvent event) {
        return new ActiveAlarmResponse(event.elementId(), event.alarmCode(), event.severity(), event.description(),
                                       event.eventId(), event.timestamp());
    }

    /**
     * The item of GET /api/alarms/history: the full event with status.
     */
    private static HistoryItemResponse toHistoryItemResponse(AlarmEvent event) {
        return new HistoryItemResponse(event.eventId(), event.elementId(), event.alarmCode(), event.severity(),
                                       event.status(), event.timestamp(), event.description());
    }

    /**
     * A bare JSON array (no wrapper, no pagination); an empty one when nothing is active.
     */
    @GetMapping("/active")
    public List<ActiveAlarmResponse> active() {
        return activeAlarms.findAllActiveAlarms().stream().map(AlarmQueryController::toActiveAlarmResponse).toList();
    }

    /**
     * The same record as /active, only this element's, sorted by alarmCode; 200 and [] for an element with none.
     */
    @GetMapping("/elements/{elementId}")
    public List<ActiveAlarmResponse> element(@PathVariable("elementId") String elementId) {
        return activeAlarms.findActiveAlarmsByElement(elementId).stream().map(AlarmQueryController::toActiveAlarmResponse).toList();
    }

    /**
     * An object {"INFO": n, "WARNING": n, "MAJOR": n, "CRITICAL": n}: all four keys, zeros included.
     */
    @GetMapping("/statistics")
    public Map<AlarmSeverity, Long> statistics() {
        return activeAlarms.countAlarmsBySeverity();
    }

    /**
     * A page of the accepted events: {@code {items, next}}. {@code after} is the {@code next} of the previous page,
     * {@code limit} is 1..500 (50 if absent). A {@code limit} out of range (Bean Validation) or an {@code after} that
     * does not decode is a 400 from {@link ApiExceptionHandler}; a {@code limit} that is not a number is a 400 from
     * Spring itself (type mismatch).
     */
    @GetMapping("/history")
    public HistoryPageResponse history(
            @RequestParam(name = "elementId", required = false) String elementId,
            @RequestParam(name = "alarmCode", required = false) String alarmCode,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT)
            @Min(value = MIN_LIMIT, message = LIMIT_MESSAGE) @Max(value = MAX_LIMIT, message = LIMIT_MESSAGE) int limit) {
        HistoryPosition position = after == null ? null : cursors.decode(after);

        HistoryPage page = history.readPage(elementId, alarmCode, position, limit);

        List<AlarmEvent> items = page.items();
        String next = page.hasNext()
                      ? cursors.encode(new HistoryPosition(items.getLast().timestamp(), items.getLast().eventId()))
                      : null;
        return new HistoryPageResponse(items.stream().map(AlarmQueryController::toHistoryItemResponse).toList(), next);
    }
}
