package com.se.telecomalarm.alarm.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import com.se.telecomalarm.alarm.api.dto.FieldProblemResponse;
import com.se.telecomalarm.alarm.service.intake.EventIdConflictException;
import com.se.telecomalarm.alarm.service.intake.UnknownAlarmException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

//The one place that turns request errors into RFC 9457 problem details (application/problem+json).
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Comparator<FieldProblemResponse> BY_FIELD_THEN_MESSAGE =
            Comparator.comparing(FieldProblemResponse::field).thenComparing(FieldProblemResponse::message);

    private static ProblemDetail invalidEvent(List<FieldProblemResponse> errors) {
        int count = errors.size();
        String detail = count == 0 ? "Request is invalid"
                                   : "Request has " + count + (count == 1 ? " invalid field" : " invalid fields");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid alarm event");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail invalidQuery(List<FieldProblemResponse> errors) {
        int count = errors.size();
        String detail = count == 0 ? "Request is invalid"
                                   : "Request has " + count + (count == 1 ? " invalid parameter" : " invalid parameters");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid query parameters");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String requestParameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        return parameter.getParameterName();
    }

    private static String failedJsonPropertyOrNull(JacksonException cause) {
        List<JacksonException.Reference> path = cause.getPath();
        for (int i = path.size() - 1; i >= 0; i--) {
            String name = path.get(i).getPropertyName();
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private static String messageFor(JacksonException cause) {
        if (cause instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null) {
            Class<?> target = mismatch.getTargetType();
            if (target.isEnum()) {
                return "must be one of " + Arrays.stream(target.getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .collect(Collectors.joining(", "));
            }
            if (Number.class.isAssignableFrom(target)) {
                return "must be an integer number";
            }
        }
        return "has an invalid value";
    }

    //Bean Validation failed: every invalid field is reported, sorted by field name.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldProblemResponse> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblemResponse(
                        error.getField(), error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid"))
                .distinct()
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
        return handleExceptionInternal(ex, invalidEvent(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * The body could not be read: empty, broken JSON, not an object, or a value of the wrong kind in a field.
     * Jackson stops at the first problem, so at most one field is reported.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        log.debug("Unreadable request body", ex);
        ProblemDetail problem = null;
        if (ex.getCause() instanceof JacksonException cause) {
            String field = failedJsonPropertyOrNull(cause);
            if (field != null) {
                problem = invalidEvent(List.of(new FieldProblemResponse(field, messageFor(cause))));
            }
        }
        if (problem == null) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON");
            problem.setTitle("Malformed request");
        }
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    //The detail never contains values of the stored event.
    @ExceptionHandler(EventIdConflictException.class)
    protected ResponseEntity<Object> handleEventIdConflict(EventIdConflictException ex, WebRequest request) {
        log.info("eventId '{}' conflicts with an earlier event", ex.eventId());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                                                                 "An event with this eventId was already received, and this request is not an exact repeat of an accepted event");
        problem.setTitle("Event id conflict");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    //The event is already stored as REJECTED; the detail does not say so, it only names the broken rule.
    @ExceptionHandler(UnknownAlarmException.class)
    protected ResponseEntity<Object> handleClearedWithoutEarlierRaised(UnknownAlarmException ex, WebRequest request) {
        log.info("eventId '{}': CLEARED for an unknown alarm, stored as rejected", ex.eventId());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                                                                 "This alarm (elementId, alarmCode) has no accepted RAISED event earlier than this CLEARED event");
        problem.setTitle("Unknown alarm");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    //A controller parameter failed Bean Validation
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldProblemResponse> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldProblemResponse(
                                requestParameterName(result.getMethodParameter()),
                                error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid")))
                .distinct()
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
        log.info("Invalid query parameters: {}", errors);
        return handleExceptionInternal(ex, invalidQuery(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    protected ResponseEntity<Object> handleInvalidQueryParameter(InvalidQueryParameterException ex, WebRequest request) {
        log.info("Invalid query parameter '{}': {}", ex.field(), ex.getMessage());
        ProblemDetail problem = invalidQuery(List.of(new FieldProblemResponse(ex.field(), ex.getMessage())));
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<Object> handleAnyOtherException(Exception ex, WebRequest request) {
        log.error("Unexpected error while handling a request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                                                                 "The request could not be processed because of an internal error");
        problem.setTitle("Internal error");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }
}
