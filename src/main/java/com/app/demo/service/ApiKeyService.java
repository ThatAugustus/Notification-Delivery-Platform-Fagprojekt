package com.app.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.app.demo.exception.ApiKeyAuthenticationException;
import com.app.demo.model.ApiKey;
import com.app.demo.model.Tenant;
import com.app.demo.repository.ApiKeyRepository;

@Service // Spring service bean
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository; // The repository handles DB queries. Spring injects it via the constructor.

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    // Hashes the raw key with SHA-256, then looks it up in the database.
    // Returns the Tenant if the key exists and is active, throws otherwise.
    public Tenant resolveTenant(String rawApiKey) {
        String hash = sha256(rawApiKey);
        ApiKey apiKey = apiKeyRepository.findByKeyHash(hash)
                .orElseThrow(() -> new ApiKeyAuthenticationException("Invalid API key"));

        if (!apiKey.isActive()) {
            throw new ApiKeyAuthenticationException("API key is revoked");
        }

        Tenant tenant = apiKey.getTenant();
        if (tenant.isDeleted()) {
            throw new ApiKeyAuthenticationException("Tenant is no longer active");
        }
        return tenant;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
