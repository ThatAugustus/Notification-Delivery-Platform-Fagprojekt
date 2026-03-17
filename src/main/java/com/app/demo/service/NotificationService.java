package com.app.demo.service;

import com.app.demo.dto.NotificationRequest;
import com.app.demo.model.enums.NotificationChannel;
import com.app.demo.model.OutboxEvent;
import com.app.demo.model.Tenant;
import com.app.demo.model.Notification;
import org.springframework.transaction.annotation.Transactional;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;

@Service // ← marks this class as a Spring service
// Business logic, transactions
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;

    public NotificationService(NotificationRepository notificationRepository,
            OutboxEventRepository outboxEventRepository) {
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
    }   

    @Transactional // ← ensures atomicity
    public Notification createNotification(Tenant tenant, NotificationRequest request) {
        // 1. Create and save the notification
        Notification notification = new Notification(tenant, NotificationChannel.valueOf(request.getChannel()),
                request.getRecipient(),
                request.getSubject(), request.getContent());
        notificationRepository.save(notification);

        // 2. Create and save the outbox event 
        OutboxEvent outbox = new OutboxEvent(notification, buildPayload(notification));
        outboxEventRepository.save(outbox);
        return notification;
    }

    private String buildPayload(Notification notification) {
        try {
            //Maps the notification to a payload in JSON format
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            //Creates a new payload with the notification data
            com.app.demo.dto.NotificationPayload payload = new com.app.demo.dto.NotificationPayload(
                notification.getId(),
                notification.getChannel(),
                notification.getTenant().getDefaultFromEmail() != null ? notification.getTenant().getDefaultFromEmail() : "noreply@notificationplatform.com",
                notification.getRecipient(),
                notification.getSubject(),
                notification.getBody()
            );
            //Returns the payload in JSON format
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize notification payload", e);
        }
    }

}
