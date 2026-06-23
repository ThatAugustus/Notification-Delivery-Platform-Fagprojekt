package com.app.demo.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled; // Mapped Diagnostic Context - used for structured logging across threads
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.service.TenantQueueLifecycleService;
import com.app.demo.model.OutboxEvent;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;

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
                String routingKey = queueLifecycleService.routingKeyFor(
                    event.getNotification().getChannel(),
                    event.getNotification().getTenant().getId());

                rabbitTemplate.convertAndSend(
                        "notifications-exchange",
                        routingKey,
                        event.getPayload()
                );
                event.markPublished();
                outboxEventRepository.save(event);
                publishedCounter.increment(); 
                var notification = event.getNotification();
                log.info("Published outbox event {} for notification {} via {}",
                        event.getId(), notification.getId(), routingKey);
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1); 
                event.setLastError(e.getMessage()); 
                outboxEventRepository.save(event); 
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            } finally { 
                MDC.remove("notificationId"); 
            }
        }
    }
}