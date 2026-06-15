package com.app.demo.exception;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
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
     * Handles: tenant exceeded their rate limit.
     * The TenantRateLimiterService throws this when a tenant has used up all their tokens.
     * Returns 429 Too Many Requests, with a "Retry-After" header indicating when they can try again.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(Map.of(
                        "status", 429,
                        "error", "Too Many Requests",
                        "message", ex.getMessage(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds(),
                        "timestamp", Instant.now().toString()
                ));
    }

    /**
     * Handles: invalid or revoked API key.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(ApiKeyAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthError(ApiKeyAuthenticationException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AdminAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAdminAuthError(AdminAuthenticationException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
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
     * Handles: a value that is syntactically valid but not acceptable,
     * e.g. an unrecognised channel string passed to NotificationChannel.valueOf().
     * Returns 400 Bad Request.
     *
     * Note: "not found" cases throw ResourceNotFoundException (handled above as 404),
     * so this handler is free to treat IllegalArgumentException as a client input error.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadArgument(IllegalArgumentException ex) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Handles: a database constraint violation, most commonly creating a tenant
     * with a name that already exists (the UNIQUE constraint on tenants.name).
     * Returns 409 Conflict instead of a generic 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return buildError(HttpStatus.CONFLICT,
                "The request conflicts with an existing record (a unique value is already in use)");
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