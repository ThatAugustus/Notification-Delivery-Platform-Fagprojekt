package com.app.demo.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.demo.dto.ApiKeyResponse;
import com.app.demo.dto.CreateApiKeyRequest;
import com.app.demo.dto.CreateApiKeyResponse;
import com.app.demo.service.ApiKeyManagementService;
import com.app.demo.service.ApiKeyManagementService.CreatedKey;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyManagementService service;

    public ApiKeyAdminController(ApiKeyManagementService service) {
        this.service = service;
    }

    // The raw key is returned only in this response, so clients must store it immediately.
    @PostMapping
    public ResponseEntity<CreateApiKeyResponse> create(@PathVariable UUID tenantId,
                                                       @Valid @RequestBody CreateApiKeyRequest request) {
        CreatedKey created = service.create(tenantId, request);

        CreateApiKeyResponse body = CreateApiKeyResponse.of(
                created.apiKey().getId(),
                tenantId,
                created.apiKey().getName(),
                created.apiKey().getPrefix(),
                created.rawKey(),
                created.apiKey().getCreatedAt()
        );
        return ResponseEntity.status(201).body(body);
    }

    // Lists active and revoked keys. Never exposes the raw key.
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> list(@PathVariable UUID tenantId) {
        List<ApiKeyResponse> keys = service.listForTenant(tenantId).stream()
                .map(ApiKeyResponse::from)
                .toList();
        return ResponseEntity.ok(keys);
    }

    // Revoking keeps the row for audit but sets active=false, so ApiKeyService rejects it on future requests.
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID tenantId,
                                       @PathVariable UUID keyId) {
        service.revoke(tenantId, keyId);
        return ResponseEntity.noContent().build();
    }
}