package com.se.telecomalarm.alarm.api.dto;

/**
 * One element of the {@code errors} array of a problem detail (HTTP 400): the same shape for an invalid POST body
 * and for invalid query parameters of GET /history. The services report their own problems in their own types;
 * {@code ApiExceptionHandler} turns them into this.
 */
public record FieldProblemResponse(
        String field,
        String message) {
}
