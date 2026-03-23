package com.app.demo.exception;
// Thrown when API key authentication fails.
public class ApiKeyAuthenticationException extends RuntimeException {
    public ApiKeyAuthenticationException(String message) {
        super(message);
    }
}