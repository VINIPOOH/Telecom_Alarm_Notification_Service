package com.se.telecomalarm.alarm.api.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.se.telecomalarm.alarm.api.InvalidQueryParameterException;
import com.se.telecomalarm.alarm.config.HistoryCursorJsonConfiguration;
import com.se.telecomalarm.alarm.service.history.HistoryPosition;
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
     * Reads a string made by {@link #encode}.
     *
     * @throws InvalidQueryParameterException the string does not decode: characters outside the base64url alphabet,
     *                                        bytes that are not JSON, a JSON of another structure
     */
    HistoryPosition decode(String text) {
        Payload payload;
        try {
            payload = json.readValue(Base64.getUrlDecoder().decode(text), Payload.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw invalidCursor();
        }
        if (payload == null || payload.timestamp() == null || payload.eventId() == null || payload.eventId().isEmpty()) {
            throw invalidCursor();
        }
        return new HistoryPosition(payload.timestamp(), payload.eventId());
    }

    String encode(HistoryPosition position) {
        byte[] bytes = json.writeValueAsBytes(new Payload(position.timestamp(), position.eventId()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The JSON inside the cursor. The keys are short ({@code ts}, {@code id}) because the cursor travels in the URL.
     * Boxed types: a missing field arrives as null and is rejected in {@link #decode}.
     */
    record Payload(
            @JsonProperty("ts") Long timestamp,
            @JsonProperty("id") String eventId) {
    }
}
