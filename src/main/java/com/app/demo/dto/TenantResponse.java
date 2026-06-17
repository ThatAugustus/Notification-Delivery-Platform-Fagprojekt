
package com.app.demo.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.demo.model.Tenant;

public record TenantResponse(
        UUID id,
        String name,
        String defaultFromEmail,
        String webhookSecret,
        boolean emailEnabled,
        boolean webhookEnabled,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getDefaultFromEmail(),
                tenant.getWebhookSecret(),
                tenant.isEmailEnabled(),
                tenant.isWebhookEnabled(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                tenant.getDeletedAt()
        );
    }
}
