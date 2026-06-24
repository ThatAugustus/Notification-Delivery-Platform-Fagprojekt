package com.app.demo.service;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.dto.NotificationRequest;
import com.app.demo.exception.ResourceNotFoundException;
import com.app.demo.model.Notification;
import com.app.demo.model.OutboxEvent;
import com.app.demo.model.Tenant;
import com.app.demo.model.enums.NotificationChannel;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RedisIdempotencyCache idempotencyCache;

    public NotificationService(NotificationRepository notificationRepository,
                               OutboxEventRepository outboxEventRepository,
                               ObjectMapper objectMapper,
                               RedisIdempotencyCache idempotencyCache) {
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.idempotencyCache = idempotencyCache;
    }

    @Transactional
    public Notification createNotification(Tenant tenant, NotificationRequest request) {

        // Idempotency check, Redis first, then PostgreSQL
        if (request.getIdempotencyKey() != null) {

            // Check Redis 
            UUID cachedId = idempotencyCache.get(tenant.getId(), request.getIdempotencyKey());
            if (cachedId != null) {
                var cached = notificationRepository.findById(cachedId);
                if (cached.isPresent()) {
                    log.info("Idempotency hit (Redis): key={} returning existing notification={}",
                            request.getIdempotencyKey(), cachedId);
                    return cached.get();
                }
                // redis had an id the db doesn't have anymore (like a rolled-back insert)
                // ignore it and fall through to the db check / create path
                log.warn("Stale Redis idempotency entry: key={} pointed to missing notification={}, ignoring",
                        request.getIdempotencyKey(), cachedId);
            }

            // Check PostgreSQL
            var existing = notificationRepository
                    .findByTenant_IdAndIdempotencyKey(tenant.getId(), request.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotency hit (DB): key={} returning existing notification={}",
                        request.getIdempotencyKey(), existing.get().getId());
                // Store in Redis so next time it's fast
                idempotencyCache.put(tenant.getId(), request.getIdempotencyKey(), existing.get().getId());
                return existing.get();
            }
        }

        NotificationChannel channel = parseChannel(request.getChannel());
        validateChannelEnabled(tenant, channel);

        // Validate webhook requests have a URL
        if (channel == NotificationChannel.WEBHOOK &&
                (request.getWebhookUrl() == null || request.getWebhookUrl().isBlank())) {
            throw new IllegalStateException("webhookUrl is required for WEBHOOK channel");
        }

        // Create and save the notification
        Notification notification = new Notification(
                tenant,
                channel,
                request.getRecipient(),
                request.getSubject(),
                request.getContent()
        );
        notification.setIdempotencyKey(request.getIdempotencyKey());
        notification.setWebhookUrl(request.getWebhookUrl());
        notificationRepository.save(notification);

        // Create and save the outbox event (same transaction)
        OutboxEvent outbox = new OutboxEvent(notification, buildPayload(notification));
        outboxEventRepository.save(outbox);

        // Cache the idempotency key in Redis for fast future lookups
        if (request.getIdempotencyKey() != null) {
            idempotencyCache.put(tenant.getId(), request.getIdempotencyKey(), notification.getId());
        }

        return notification;
    }

    public Notification getNotification(Tenant tenant, UUID id) {
        return notificationRepository.findByIdAndTenant_Id(id, tenant.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
    }

    private String buildPayload(Notification notification) {
        try {
            NotificationPayload payload = new NotificationPayload(
                    notification.getId(),
                    notification.getChannel(),
                    notification.getTenant().getId(),
                    notification.getTenant().getDefaultFromEmail() != null
                            ? notification.getTenant().getDefaultFromEmail()
                            : "noreply@notificationplatform.com",
                    notification.getRecipient(),
                    notification.getSubject(),
                    notification.getBody(),
                    notification.getWebhookUrl(),
                    notification.getTenant().getWebhookSecret()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize notification payload", e);
        }
    }
    
    private NotificationChannel parseChannel(String channel) {
        try {
            return NotificationChannel.valueOf(channel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown notification channel: " + channel);
        }
    }

    private void validateChannelEnabled(Tenant tenant, NotificationChannel channel) {
        if (channel == NotificationChannel.EMAIL && !tenant.isEmailEnabled()) {
            throw new IllegalStateException("EMAIL channel is disabled for this tenant");
        }
        if (channel == NotificationChannel.WEBHOOK && !tenant.isWebhookEnabled()) {
            throw new IllegalStateException("WEBHOOK channel is disabled for this tenant");
        }
    }
}
