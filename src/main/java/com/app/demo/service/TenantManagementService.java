
package com.app.demo.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.dto.CreateTenantRequest;
import com.app.demo.dto.UpdateTenantRequest;
import com.app.demo.exception.ResourceNotFoundException;
import com.app.demo.model.Tenant;
import com.app.demo.repository.TenantRepository;

@Service
public class TenantManagementService {

    private static final Logger log = LoggerFactory.getLogger(TenantManagementService.class);

    private final TenantRepository tenantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public TenantManagementService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant create(CreateTenantRequest request) {
        Tenant tenant = new Tenant(request.getName());
        tenant.setDefaultFromEmail(request.getDefaultFromEmail());
        tenant.setWebhookSecret(generateWebhookSecret());

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant: id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    public List<Tenant> listActive() {
        return tenantRepository.findAllByDeletedAtIsNull();
    }

    public Tenant getActive(UUID id) {
        return tenantRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + id));
    }

    // Used by the restore endpoint — needs to find soft-deleted rows too.
    public Tenant getAny(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + id));
    }

    @Transactional
    public Tenant update(UUID id, UpdateTenantRequest request) {
        Tenant tenant = getActive(id);

        if (request.getName() != null) {
            tenant.setName(request.getName());
        }
        if (request.getDefaultFromEmail() != null) {
            tenant.setDefaultFromEmail(request.getDefaultFromEmail());
        }

        log.info("Updated tenant: id={}", id);
        return tenant; // managed entity — changes flush on transaction commit
    }

    @Transactional
    public void softDelete(UUID id) {
        Tenant tenant = getActive(id);
        tenant.softDelete();
        log.info("Soft-deleted tenant: id={}", id);
    }

    @Transactional
    public Tenant restore(UUID id) {
        Tenant tenant = getAny(id);
        if (!tenant.isDeleted()) {
            // Not an error — just idempotent. Log and return.
            log.info("Tenant {} is already active; restore is a no-op", id);
            return tenant;
        }
        tenant.restore();
        log.info("Restored tenant: id={}", id);
        return tenant;
    }

    // Generates a 256-bit random secret, base64-encoded. Used as the HMAC key
    // for webhook signing. Matches the existing webhookSecret format.
    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
