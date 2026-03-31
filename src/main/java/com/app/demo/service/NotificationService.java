package com.app.demo.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.dto.NotificationRequest;
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

    public NotificationService(NotificationRepository notificationRepository,
                               OutboxEventRepository outboxEventRepository,
                               ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Notification createNotification(Tenant tenant, NotificationRequest request) {

        // Idempotency check
        if (request.getIdempotencyKey() != null) {
            var existing = notificationRepository
                    .findByTenant_IdAndIdempotencyKey(tenant.getId(), request.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotency hit: key={} returning existing notification={}",
                        request.getIdempotencyKey(), existing.get().getId());
                return existing.get();
            }
        }

        // Validate webhook requests have a URL
        if (request.getChannel().equals("WEBHOOK") &&
                (request.getWebhookUrl() == null || request.getWebhookUrl().isBlank())) {
            throw new IllegalStateException("webhookUrl is required for WEBHOOK channel");
        }

        // 1. Create and save the notification
        Notification notification = new Notification(
                tenant,
                NotificationChannel.valueOf(request.getChannel()),
                request.getRecipient(),
                request.getSubject(),
                request.getContent()
        );
        notification.setIdempotencyKey(request.getIdempotencyKey());
        notification.setWebhookUrl(request.getWebhookUrl());
        notificationRepository.save(notification);

        // 2. Create and save the outbox event (same transaction)
        OutboxEvent outbox = new OutboxEvent(notification, buildPayload(notification));
        outboxEventRepository.save(outbox);

        return notification;
    }

    public Notification getNotification(Tenant tenant, UUID id) {
        return notificationRepository.findByIdAndTenant_Id(id, tenant.getId())
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));
    }

    private String buildPayload(Notification notification) {
        try {
            NotificationPayload payload = new NotificationPayload(
                    notification.getId(),
                    notification.getChannel(),
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
}