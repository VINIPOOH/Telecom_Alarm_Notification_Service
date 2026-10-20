package com.se.telecomalarm.alarm.api;

/**
 * A query parameter is invalid in a way Bean Validation does not check, for example a history cursor that does not
 * decode: HTTP 400 in {@link ApiExceptionHandler}. The message is a fixed text, never the value the client sent.
 */
public class InvalidQueryParameterException extends RuntimeException {

    private final String field;

    public InvalidQueryParameterException(String field, String fixedMessage) {
        super(fixedMessage);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
