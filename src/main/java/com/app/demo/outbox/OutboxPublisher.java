package com.app.demo.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.model.OutboxEvent;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import com.app.demo.service.TenantQueueLifecycleService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TenantQueueLifecycleService queueLifecycleService;
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, 
                           NotificationRepository notificationRepository, 
                           RabbitTemplate rabbitTemplate,
                           TenantQueueLifecycleService queueLifecycleService,
                           MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueLifecycleService = queueLifecycleService;
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
                var notification = event.getNotification();

                // if the queue isn't there we can't publish. for a deleted tenant it's never coming back
                // so just fail it, but for an active tenant it's probably not set up yet so try again later
                if (!queueLifecycleService.destinationQueueExists(
                        notification.getChannel(), notification.getTenant().getId())) {
                    if (notification.getTenant().isDeleted()) {
                        event.markPublished();
                        event.setLastError("destination queue removed after tenant deletion");
                        outboxEventRepository.save(event);
                        notification.setStatus(NotificationStatus.FAILED);
                        notificationRepository.save(notification);
                        log.warn("Failed outbox event {}: destination queue gone for notification {}",
                                event.getId(), notification.getId());
                    }
                    continue;
                }

                String routingKey = queueLifecycleService.routingKeyFor(
                    notification.getChannel(),
                    notification.getTenant().getId());

                rabbitTemplate.convertAndSend(
                        "notifications-exchange",
                        routingKey,
                        event.getPayload()
                );
                event.markPublished();
                outboxEventRepository.save(event);
                publishedCounter.increment(); //TODO: maybe this should be somewhere else, since this code is not affected if the transaction fails, and it will count the message as published even if it's not?

                notificationRepository.markQueuedIfAccepted(notification.getId());

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