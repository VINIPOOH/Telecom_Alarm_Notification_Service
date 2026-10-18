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

/**
 * The one place that turns request errors into RFC 9457 problem details (application/problem+json).
 * <p>
 * It extends {@link ResponseEntityExceptionHandler}, so every built-in Spring MVC exception (for example 415 for a
 * wrong Content-Type) is rendered as a problem detail too; only the cases below are customised.
 * Nothing from the exception (message, class names, parser position) goes into the response body.
 */
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

    /**
     * The request parameter name of a controller method parameter: the name of its {@code @RequestParam}, else the
     * Java name.
     */
    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        return parameter.getParameterName();
    }

    /**
     * The JSON property Jackson was reading when it failed, or null if the failure is not tied to a property.
     */
    private static String fieldOf(JacksonException cause) {
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

    /**
     * Bean Validation failed: every invalid field is reported, sorted by field name.
     */
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
            String field = fieldOf(cause);
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

    /**
     * The eventId is taken and the request is not an exact repeat of an accepted event: 409.
     * The detail says only that; it never contains values of the stored event (and no technical details).
     */
    @ExceptionHandler(EventIdConflictException.class)
    protected ResponseEntity<Object> handleEventIdConflict(EventIdConflictException ex, WebRequest request) {
        log.info("eventId '{}' conflicts with an earlier event", ex.eventId());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                                                                 "An event with this eventId was already received, and this request is not an exact repeat of an accepted event");
        problem.setTitle("Event id conflict");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * A CLEARED without an earlier accepted RAISED of its pair: 409. The event is already stored as REJECTED; the
     * detail does not say so (internal state), it only names the rule the event broke.
     */
    @ExceptionHandler(UnknownAlarmException.class)
    protected ResponseEntity<Object> handleUnknownAlarm(UnknownAlarmException ex, WebRequest request) {
        log.info("eventId '{}': CLEARED for an unknown alarm, stored as rejected", ex.eventId());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                                                                 "This alarm (elementId, alarmCode) has no accepted RAISED event earlier than this CLEARED event");
        problem.setTitle("Unknown alarm");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * Bean Validation of a controller method parameter failed (for example {@code limit} of /history out of range):
     * 400 with one entry per invalid parameter in {@code errors}, by its request parameter name.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldProblemResponse> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldProblemResponse(
                                parameterName(result.getMethodParameter()),
                                error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid")))
                .distinct()
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
        log.info("Invalid query parameters: {}", errors);
        return handleExceptionInternal(ex, invalidQuery(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A query parameter that Bean Validation does not check is invalid (an {@code after} of /history that does not
     * decode): 400 in the same form. The text is fixed: the value the client sent is not repeated.
     */
    @ExceptionHandler(InvalidQueryParameterException.class)
    protected ResponseEntity<Object> handleInvalidQueryParameter(InvalidQueryParameterException ex, WebRequest request) {
        log.info("Invalid query parameter '{}': {}", ex.field(), ex.getMessage());
        ProblemDetail problem = invalidQuery(List.of(new FieldProblemResponse(ex.field(), ex.getMessage())));
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }
}
