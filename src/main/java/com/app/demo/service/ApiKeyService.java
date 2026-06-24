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

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    // hash the raw key with SHA-256 and look it up in the db
    // returns the tenant if the key exists and is active, otherwise throws
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
