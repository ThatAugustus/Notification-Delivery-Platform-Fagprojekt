package com.app.demo.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    public TenantManagementService(TenantRepository tenantRepository, ApplicationEventPublisher eventPublisher) {
        this.tenantRepository = tenantRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Tenant create(CreateTenantRequest request) {
        Tenant tenant = new Tenant(request.getName());
        tenant.setDefaultFromEmail(request.getDefaultFromEmail());
        tenant.setEmailEnabled(request.getEmailEnabled() == null || request.getEmailEnabled());
        tenant.setWebhookEnabled(request.getWebhookEnabled() == null || request.getWebhookEnabled());
        tenant.setWebhookSecret(generateWebhookSecret());

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant: id={} name={}", saved.getId(), saved.getName());
        eventPublisher.publishEvent(new TenantLifecycleEvent(saved, TenantLifecycleEvent.Action.CREATED));
        return saved;
    }

    public List<Tenant> listActive() {
        return tenantRepository.findAllByDeletedAtIsNull();
    }

    public Tenant getActive(UUID id) {
        return tenantRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + id));
    }

    // Restore needs this variant because it has to find soft-deleted rows too.
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
        if (request.getEmailEnabled() != null) {
            tenant.setEmailEnabled(request.getEmailEnabled());
        }
        if (request.getWebhookEnabled() != null) {
            tenant.setWebhookEnabled(request.getWebhookEnabled());
        }

        log.info("Updated tenant: id={}", id);
        eventPublisher.publishEvent(new TenantLifecycleEvent(tenant, TenantLifecycleEvent.Action.UPDATED));
        return tenant; // managed entity, so changes flush on transaction commit
    }

    @Transactional
    public void softDelete(UUID id) {
        Tenant tenant = getActive(id);
        tenant.softDelete();
        log.info("Soft-deleted tenant: id={}", id);
        eventPublisher.publishEvent(new TenantLifecycleEvent(tenant, TenantLifecycleEvent.Action.SOFT_DELETED));
    }

    @Transactional
    public Tenant restore(UUID id) {
        Tenant tenant = getAny(id);
        if (!tenant.isDeleted()) {
            // Already active, so restore is a no-op rather than an error.
            log.info("Tenant {} is already active; restore is a no-op", id);
            return tenant;
        }
        tenant.restore();
        log.info("Restored tenant: id={}", id);
        eventPublisher.publishEvent(new TenantLifecycleEvent(tenant, TenantLifecycleEvent.Action.RESTORED));
        return tenant;
    }

    // 256-bit random secret, base64url-encoded, used as the HMAC key for webhook signing.
    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}