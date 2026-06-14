
package com.app.demo.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.demo.model.ApiKey;

public record ApiKeyResponse(
        UUID id,
        UUID tenantId,
        String name,
        String prefix,
        boolean active,
        Instant createdAt,
        Instant revokedAt
) {
    public static ApiKeyResponse from(ApiKey key) {
        return new ApiKeyResponse(
                key.getId(),
                key.getTenant().getId(),
                key.getName(),
                key.getPrefix(),
                key.isActive(),
                key.getCreatedAt(),
                key.getRevokedAt()
        );
    }
}
