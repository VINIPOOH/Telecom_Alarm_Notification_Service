package com.se.telecomalarm.alarm.api.controller;

import jakarta.validation.Valid;

import com.se.telecomalarm.alarm.api.ApiExceptionHandler;
import com.se.telecomalarm.alarm.api.dto.AlarmEventRequest;
import com.se.telecomalarm.alarm.api.dto.AlarmEventResponse;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.intake.AlarmEventResult;
import com.se.telecomalarm.alarm.service.intake.AlarmEventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmEventService service;

    public AlarmController(AlarmEventService service) {
        this.service = service;
    }

    /**
     * intake of alarm events.
     * Order of work: the Content-Type check (consumes) -> Jackson reads the body into {@link AlarmEventRequest}
     * -> Bean Validation (@Valid) -> only then {@link AlarmEventService}. Errors of the first three steps are
     * turned into problem details by {@link ApiExceptionHandler}; an invalid event never reaches the service.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmEventResponse> receive(@Valid @RequestBody AlarmEventRequest request) {
        AlarmEventResult result = service.accept(convertToAlarmEvent(request));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new AlarmEventResponse(request.eventId(), result.notification()));
    }

    private AlarmEvent convertToAlarmEvent(AlarmEventRequest request) {
        return new AlarmEvent(request.eventId(), request.elementId(), request.alarmCode(), request.severity(),
                              request.status(), request.timestamp(), request.description());
    }
}
