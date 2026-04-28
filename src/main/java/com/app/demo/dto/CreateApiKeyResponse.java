
package com.app.demo.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyResponse(
        UUID id,
        UUID tenantId,
        String name,
        String prefix,
        String rawKey,
        Instant createdAt,
        String warning
) {
    public static CreateApiKeyResponse of(UUID id, UUID tenantId, String name,
                                          String prefix, String rawKey, Instant createdAt) {
        return new CreateApiKeyResponse(
                id, tenantId, name, prefix, rawKey, createdAt,
                "This is the only time the raw key will be shown. Store it securely."
        );
    }
}
