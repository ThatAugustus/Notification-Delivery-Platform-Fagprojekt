package com.app.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
                return existing.get(); // same request, return what we already created
            }
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
                    notification.getBody()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize notification payload", e);
        }
    }
}