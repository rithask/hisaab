package com.hisaab.dataapi.exception;

import com.hisaab.config.ApiTokenFilter;
import com.hisaab.dataapi.controller.DataApiController;
import com.hisaab.common.exception.NotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

/** The Data API answers errors as standard problem details (RFC 9457), unlike the app's own {"error": ...}. */
@RestControllerAdvice(assignableTypes = DataApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DataApiExceptionHandler.class);

    @ExceptionHandler(TokenForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(TokenForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Two requests with the same Idempotency-Key at the same moment: the second is told to try again. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Data API request refused by a database constraint: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "A request with this Idempotency-Key is being processed. Try again shortly.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> notFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** {@code BadRequestException}, {@code NotAllowedException}, and a library's own invalid-argument error. */
    @ExceptionHandler({IllegalArgumentException.class, NotAllowedException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        log.debug("Data API request unreadable: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "The request isn't valid. See docs/api.md for what each endpoint takes.");
    }

    /** A body that isn't JSON: 415 (RFC 9110 §15.5.16), saying what to send. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(Exception e) {
        note("Body isn't JSON");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header(org.springframework.http.HttpHeaders.ACCEPT, "application/json")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the body as JSON, with Content-Type: application/json."));
    }

    /**
     * Anything else. Spring's own errors (e.g. 405 wrong method) keep their status; a real failure is a 500 with
     * its stack trace in the log, and the client gets a problem detail that doesn't leak internals.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return problem(status, er.getBody().getDetail() == null ? status.getReasonPhrase() : er.getBody().getDetail());
        }
        String ref = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);
        log.error("Data API request failed [ref {}]: {}", ref, e.toString(), e);
        note(e.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server (ref " + ref + ")."));
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        note(detail);
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** Keeps the reason for the request's log line (see AuthLog.tokenUsed). */
    private static void note(String reason) {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs != null && reason != null) {
            attrs.setAttribute(ApiTokenFilter.ERROR, reason,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }
    }
}
