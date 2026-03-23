package com.app.demo.retry;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.model.Notification;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class RetryPoller {

    private static final Logger log = LoggerFactory.getLogger(RetryPoller.class);
    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RetryPoller(
            NotificationRepository notificationRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    // Runs every 2 seconds to find and re-queue notifications that are due for retry
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void pollAndRetry() {
        List<Notification> due = notificationRepository.findRetryReady(BATCH_SIZE);

        if (!due.isEmpty()) {
            log.info("RetryPoller found {} notifications due for retry", due.size());
        }

        for (Notification notification : due) {
            try {
                String payload = buildPayload(notification);
                String routingKey = "notification." + notification.getChannel().name().toLowerCase();

                rabbitTemplate.convertAndSend("notifications-exchange", routingKey, payload);

                notification.setStatus(NotificationStatus.QUEUED);
                notification.setNextRetryAt(null);
                notificationRepository.save(notification);

                log.info("Re-queued notification {} for retry attempt #{} via {}",
                        notification.getId(), notification.getRetryCount() + 1, routingKey);

            } catch (Exception e) {
                log.error("Failed to re-queue notification {} for retry: {}",
                        notification.getId(), e.getMessage());
            }
        }
    }

    // Helper method to build the JSON payload for the retry message
    private String buildPayload(Notification notification) {
        try {
            String fromEmail = notification.getTenant().getDefaultFromEmail() != null
                    ? notification.getTenant().getDefaultFromEmail()
                    : "noreply@notificationplatform.com";

            NotificationPayload payload = new NotificationPayload(
                    notification.getId(),
                    notification.getChannel(),
                    fromEmail,
                    notification.getRecipient(),
                    notification.getSubject(),
                    notification.getBody()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize retry payload for " + notification.getId(), e);
        }
    }
}
