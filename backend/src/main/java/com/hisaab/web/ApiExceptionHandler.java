package com.hisaab.web;

import com.hisaab.statement.PdfText.PasswordRequired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** An upload that isn't a proper file upload, or is missing its file: the request's fault. */
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<Map<String, String>> badUpload(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Choose a file to upload."));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request: " + e.getMostSpecificCause().getMessage()));
    }

    /** A statement PDF needs (another) password: the client asks for one and retries. */
    @ExceptionHandler(PasswordRequired.class)
    ResponseEntity<Map<String, String>> password(PasswordRequired e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage(), "code", "PASSWORD"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        // Usually a duplicate name, but log the real cause so a genuine bug doesn't hide behind that message.
        log.warn("Request refused by a database constraint: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "That conflicts with existing data (for example a duplicate name)"));
    }

    /**
     * Anything else. Spring's own errors (404, 405, 415…) keep their status; a real failure is logged with its
     * stack trace and the endpoint, and the screen gets a plain message instead of the server's internals.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e, jakarta.servlet.http.HttpServletRequest req) {
        if (e instanceof org.springframework.web.ErrorResponse er) {
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", detail == null ? "Request failed" : detail));
        }
        log.error("Request failed: {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Something went wrong on the server. Try again; if it keeps happening, the server log says why."));
    }
}
