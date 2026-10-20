package com.se.telecomalarm.alarm.service.notification;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * On a failure: a 4xx/5xx throws {@code RestClientResponseException}; a timeout, a broken connection or a failed TLS
 * handshake throws {@code ResourceAccessException}. A 1xx/3xx comes back normally, so the caller checks for 2xx itself
 * ({@link NotificationSenderService}).
 */
public interface NotificationClient {

    //The HTTP Service group; the name is the middle part of {@code spring.http.serviceclient.notifications.*}.
    String GROUP = "notifications";

    @PostExchange(url = "/external/notifications", contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(@RequestBody AlarmEventNotification notification);
}
