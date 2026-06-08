package com.app.demo.exception;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Catches exceptions thrown anywhere in the controller layer
 * and converts them into clean JSON error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles: invalid or revoked API key.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(ApiKeyAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthError(ApiKeyAuthenticationException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * Handles: missing API-Key header entirely.
     * Spring throws this automatically when a required @RequestHeader is absent.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, "Missing required header: X-API-Key");
    }

    /**
     * Handles: @Valid failures on the request body.
     * Like if 'recipient' is blank or 'channel' is null.
     * Collects all field errors into a readable message.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        // Collect every field error into "field: message" strings
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return buildError(HttpStatus.BAD_REQUEST, details);
    }

    /**
     * Handles: notification not found (for the GET endpoint).
     * Returns 404 Not Found.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException ex) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Catch-all for anything we didn't explicitly handle above.
     * Logs the full stack trace so we can debug, but returns
     * a generic message to the client (never leak internals).
     * Returns 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleEverythingElse(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    /**
     * Helper — builds a consistent JSON error body:
     * {
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Invalid API key",
     *   "timestamp": "2025-..."
     * }
     */
    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalStateException ex) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

}