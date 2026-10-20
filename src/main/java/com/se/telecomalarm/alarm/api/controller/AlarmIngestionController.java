package com.se.telecomalarm.alarm.api.controller;

import jakarta.validation.Valid;

import com.se.telecomalarm.alarm.api.dto.AlarmEventRequest;
import com.se.telecomalarm.alarm.api.dto.AlarmEventResponse;
import com.se.telecomalarm.alarm.service.AlarmEvent;
import com.se.telecomalarm.alarm.service.intake.AlarmEventIntakeResult;
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
public class AlarmIngestionController {

    private final AlarmEventService service;

    public AlarmIngestionController(AlarmEventService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmEventResponse> receive(@Valid @RequestBody AlarmEventRequest request) {
        AlarmEventIntakeResult result = service.accept(convertToAlarmEvent(request));
        HttpStatus status = result.isNewEvent() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new AlarmEventResponse(request.eventId(), result.notificationOutcome()));
    }

    private AlarmEvent convertToAlarmEvent(AlarmEventRequest request) {
        return new AlarmEvent(request.eventId(), request.elementId(), request.alarmCode(), request.severity(),
                              request.status(), request.timestamp(), request.description());
    }
}
