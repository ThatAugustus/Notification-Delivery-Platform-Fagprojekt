package com.app.demo.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.model.OutboxEvent;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            NotificationRepository notificationRepository,
            RabbitTemplate rabbitTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedFalse();
        for (OutboxEvent event : pending) {
            try {
                String routingKey = "notification." + event.getNotification().getChannel().name().toLowerCase();

                rabbitTemplate.convertAndSend(
                        "notifications-exchange",
                        routingKey,
                        event.getPayload()
                );
                event.markPublished();
                outboxEventRepository.save(event);

                var notification = event.getNotification();
                notification.setStatus(NotificationStatus.QUEUED);
                notificationRepository.save(notification);

                log.info("Published outbox event {} for notification {} via {}",
                        event.getId(), notification.getId(), routingKey);
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage());
                outboxEventRepository.save(event);
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}