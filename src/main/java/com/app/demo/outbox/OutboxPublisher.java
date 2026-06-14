package com.app.demo.outbox;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC; // Mapped Diagnostic Context - used for structured logging across threads
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
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, 
                           NotificationRepository notificationRepository, 
                           RabbitTemplate rabbitTemplate,
                           MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.publishedCounter = Counter.builder("outbox.published")
                .description("Messages published to queue by the outbox poller")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 1000) // 1000ms = 1 second.
    @Transactional // spring handles the db transaction, ensuring atomicity
    public void pollAndPublish() {
        // grabs a batch of unpublished events from DB into a list
        List<OutboxEvent> pending = outboxEventRepository.findUnpublishedBatch(BATCH_SIZE);
        for (OutboxEvent event : pending) {
            MDC.put("notificationId", event.getNotification().getId().toString());
            try {
                String routingKey = "notification." + event.getNotification().getChannel().name().toLowerCase();

                rabbitTemplate.convertAndSend(
                        "notifications-exchange",
                        routingKey,
                        event.getPayload()
                );
                event.markPublished();
                outboxEventRepository.save(event);
                publishedCounter.increment(); //TODO: maybe this should be somewhere else, since this code is not affected if the transaction fails, and it will count the message as published even if it's not?

                var notification = event.getNotification();
                notification.setStatus(NotificationStatus.QUEUED);
                notificationRepository.save(notification);

                log.info("Published outbox event {} for notification {} via {}",
                        event.getId(), notification.getId(), routingKey);
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1); // Increment retry count to DB 
                event.setLastError(e.getMessage()); // Set the last error message to DB
                outboxEventRepository.save(event); // Save the updated event to DB
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            } finally { // finally: Always runs whether the try-block succeeds or fails
                MDC.remove("notificationId"); // Clean up MDC context
            }
        }
    }
}