package com.hisaab.web;

import com.hisaab.service.DataApiService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/** The Data API answers errors as standard problem details (RFC 9457), unlike the app's own {"error": ...}. */
@RestControllerAdvice(assignableTypes = DataApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataApiErrors {
    @ExceptionHandler(DataApiService.Forbidden.class)
    ResponseEntity<ProblemDetail> forbidden(DataApiService.Forbidden e) {
        return problem(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(DataApiService.KeyReused.class)
    ResponseEntity<ProblemDetail> keyReused(DataApiService.KeyReused e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Two requests with the same Idempotency-Key at the same moment: the second is told to try again. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(Exception e) {
        return problem(HttpStatus.CONFLICT, "A request with this Idempotency-Key is being processed. Try again shortly.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> notFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "The request isn't valid. See docs/api.md for what each endpoint takes.");
    }

    /** A body that isn't JSON: 415 (RFC 9110 §15.5.16), saying what to send. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(Exception e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header(org.springframework.http.HttpHeaders.ACCEPT, "application/json")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the body as JSON, with Content-Type: application/json."));
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
