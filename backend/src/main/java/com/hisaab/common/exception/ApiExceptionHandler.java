package com.hisaab.common.exception;

import com.hisaab.statements.exception.PasswordRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every error from the app's own API ends here, so each one is logged once, with the request it came from, and
 * the app gets {@code {"error": "…"}} with a message a person can act on. A mistake in the request (not found,
 * invalid, not allowed now) is a warning line; anything unexpected is logged with its stack trace and a short
 * reference that's also in the message, so the log entry can be found. Internals never reach the screen.
 * (The Data API has its own handler, {@code DataApiExceptionHandler}, which answers in problem-detail form.)
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** {@link NotFoundException}, and lookups elsewhere that report "not found" the same way. */
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e, HttpServletRequest req) {
        return refused(HttpStatus.NOT_FOUND, e.getMessage(), req);
    }

    /** {@link BadRequestException} and {@link NotAllowedException} (and a library's own invalid-argument error). */
    @ExceptionHandler({IllegalArgumentException.class, NotAllowedException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e, HttpServletRequest req) {
        return refused(HttpStatus.BAD_REQUEST, e.getMessage(), req);
    }

    /** An upload that isn't a proper file upload, or is missing its file: the request's fault. */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<Map<String, String>> badUpload(Exception e, HttpServletRequest req) {
        log.warn("{} {} -> 400: upload without a file ({})", req.getMethod(), req.getRequestURI(), e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Choose a file to upload.");
    }

    /** A body that isn't the JSON the endpoint takes: the details go to the log, not the screen. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        log.warn("{} {} -> 400: unreadable request body ({})", req.getMethod(), req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return body(HttpStatus.BAD_REQUEST, "The request couldn't be read. Reload the page and try again.");
    }

    /** A statement PDF needs (another) password: the app asks for one and retries. */
    @ExceptionHandler(PasswordRequiredException.class)
    ResponseEntity<Map<String, String>> password(PasswordRequiredException e, HttpServletRequest req) {
        log.info("{} {} -> 422: statement needs a password", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage(), "code", "PASSWORD"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e, HttpServletRequest req) {
        // Usually a duplicate name, but the real cause is logged so a genuine bug doesn't hide behind that message.
        log.warn("{} {} -> 409: refused by a database constraint ({})", req.getMethod(), req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "That conflicts with existing data (for example a duplicate name)");
    }

    /**
     * Anything else. Spring's own errors (no such URL, wrong method, …) keep their status and are a warning line; a
     * real failure is logged with its stack trace under a short reference, which the message gives.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er) {
            String detail = er.getBody().getDetail();
            return refused(er.getStatusCode(), detail == null ? "Request failed" : detail, req);
        }
        String ref = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);
        log.error("{} {} -> 500 [ref {}]: {}", req.getMethod(), req.getRequestURI(), ref, e.toString(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on the server. Try again; if it keeps happening, look for “ref " + ref + "” in the server log.");
    }

    /** A request refused for a reason the user can act on: one warning line, and their message as is. */
    private static ResponseEntity<Map<String, String>> refused(HttpStatusCode status, String message, HttpServletRequest req) {
        String shown = String.valueOf(message);
        log.warn("{} {} -> {}: {}", req.getMethod(), req.getRequestURI(), status.value(), shown);
        return body(status, shown);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
