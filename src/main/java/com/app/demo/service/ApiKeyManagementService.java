package com.app.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.dto.CreateApiKeyRequest;
import com.app.demo.exception.ResourceNotFoundException;
import com.app.demo.model.ApiKey;
import com.app.demo.model.Tenant;
import com.app.demo.repository.ApiKeyRepository;

@Service
public class ApiKeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyManagementService.class);

    private static final String KEY_PREFIX = "ndp_";
    private static final int RANDOM_BYTES = 24; // 192 bits of entropy before encoding
    private static final int DISPLAY_PREFIX_LENGTH = 8;

    private final ApiKeyRepository apiKeyRepository;
    private final TenantManagementService tenantManagementService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyManagementService(ApiKeyRepository apiKeyRepository,
                                   TenantManagementService tenantManagementService) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantManagementService = tenantManagementService;
    }

    // Returns the saved key plus the raw key. The raw key is only ever returned here: never stored, never shown again.
    @Transactional
    public CreatedKey create(UUID tenantId, CreateApiKeyRequest request) {
        Tenant tenant = tenantManagementService.getActive(tenantId);

        String rawKey = generateRawKey();
        String hash = sha256(rawKey);
        String displayPrefix = rawKey.substring(0, DISPLAY_PREFIX_LENGTH);

        ApiKey apiKey = new ApiKey(tenant, hash, displayPrefix, request.getName());
        ApiKey saved = apiKeyRepository.save(apiKey);

        log.info("Created API key: id={} tenant={} prefix={}",
                saved.getId(), tenantId, displayPrefix);

        return new CreatedKey(saved, rawKey);
    }

    public List<ApiKey> listForTenant(UUID tenantId) {
        tenantManagementService.getActive(tenantId);
        return apiKeyRepository.findAllByTenant_Id(tenantId);
    }

    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        tenantManagementService.getActive(tenantId);

        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found: " + keyId));

        // Defend against cross-tenant revocation (one tenant revoking another's key).
        if (!key.getTenant().getId().equals(tenantId)) {
            throw new ResourceNotFoundException("API key not found: " + keyId);
        }

        key.revoke();
        log.info("Revoked API key: id={} tenant={}", keyId, tenantId);
    }

    private String generateRawKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    public record CreatedKey(ApiKey apiKey, String rawKey) {
    }
}