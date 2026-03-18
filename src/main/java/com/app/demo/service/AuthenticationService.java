package com.app.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.app.demo.model.ApiKey;
import com.app.demo.model.Tenant;
import com.app.demo.repository.ApiKeyRepository;

/**
 * Handles API key validation, independent of HTTP.
 * 
 * Hashes the raw key with SHA-256 and looks it up in the database.
 * This service is the single point of truth for "is this key valid?" —
 * making it easy to unit-test and to reuse from different entry points
 * (HTTP filter, CLI commands, admin tools, etc.).
 */
@Service
public class AuthenticationService {

    private final ApiKeyRepository apiKeyRepository;

    public AuthenticationService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Validates a raw API key and returns the associated Tenant.
     *
     * @param rawApiKey The plaintext key sent by the client in the X-API-Key header
     * @return The Tenant associated with the key
     * @throws IllegalArgumentException if the key is null or blank
     * @throws org.springframework.security.access.AccessDeniedException if key is invalid or revoked
     */
    public Tenant authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("API key is required");
        }

        String hash = sha256(rawApiKey);
        ApiKey apiKey = apiKeyRepository.findByKeyHashAndActiveTrue(hash)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Invalid or revoked API key"));

        return apiKey.getTenant();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in all Java 8+ runtimes
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
