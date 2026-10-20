package com.se.telecomalarm.alarm.api.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.se.telecomalarm.alarm.api.InvalidQueryParameterException;
import com.se.telecomalarm.alarm.config.HistoryCursorJsonConfiguration;
import com.se.telecomalarm.alarm.service.history.HistoryCursor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class HistoryCursorCodec {

    private final JsonMapper json;

    HistoryCursorCodec(@Qualifier(HistoryCursorJsonConfiguration.HISTORY_CURSOR_JSON_MAPPER_QUALIFIER) JsonMapper json) {
        this.json = json;
    }

    private static InvalidQueryParameterException invalidCursor() {
        return new InvalidQueryParameterException("after", "is not a valid cursor");
    }

    /**
     * @throws InvalidQueryParameterException the string does not decode: characters outside the base64url alphabet,
     *                                        bytes that are not JSON, a JSON of another structure
     */
    HistoryCursor decode(String text) {
        PagePointer pagePointer;
        try {
            pagePointer = json.readValue(Base64.getUrlDecoder().decode(text), PagePointer.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw invalidCursor();
        }
        if (pagePointer == null || pagePointer.timestamp() == null || pagePointer.eventId() == null || pagePointer.eventId().isEmpty()) {
            throw invalidCursor();
        }
        return new HistoryCursor(pagePointer.timestamp(), pagePointer.eventId());
    }

    String encode(HistoryCursor position) {
        byte[] bytes = json.writeValueAsBytes(new PagePointer(position.eventTimestampMillis(), position.tieBreakerEventId()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record PagePointer(
            @JsonProperty("ts") Long timestamp,
            @JsonProperty("id") String eventId) {
    }
}
