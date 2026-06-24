package com.app.demo.outbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.config.OutboxFairnessConfig;
import com.app.demo.model.OutboxEvent;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import com.app.demo.repository.PendingOutboxTenantView;
import com.app.demo.service.TenantQueueLifecycleService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 250;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TenantQueueLifecycleService queueLifecycleService;
    private final OutboxFairnessConfig outboxFairnessConfig;
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, 
                           NotificationRepository notificationRepository, 
                           RabbitTemplate rabbitTemplate,
                           TenantQueueLifecycleService queueLifecycleService,
                           OutboxFairnessConfig outboxFairnessConfig,
                           MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueLifecycleService = queueLifecycleService;
        this.outboxFairnessConfig = outboxFairnessConfig;
        this.publishedCounter = Counter.builder("outbox.published")
                .description("Messages published to queue by the outbox poller")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 200) // ms
    @Transactional // spring handles the db transaction, ensuring atomicity
    public void pollAndPublish() {
        List<PendingOutboxTenantView> pendingTenants = outboxEventRepository.findPendingTenants(BATCH_SIZE);
        if (pendingTenants.isEmpty()) {
            return;
        }

        Map<UUID, List<OutboxEvent>> tenantEvents = new LinkedHashMap<>();
        List<UUID> weightedTenantOrder = new ArrayList<>();

        for (PendingOutboxTenantView tenantView : pendingTenants) {
            int weight = outboxFairnessConfig.weightFor(tenantView.getTenantId(), tenantView.getTenantName());
            List<OutboxEvent> events = outboxEventRepository.findUnpublishedBatchForTenant(
                    tenantView.getTenantId(),
                    BATCH_SIZE);

            if (events.isEmpty()) {
                continue;
            }

            tenantEvents.put(tenantView.getTenantId(), new ArrayList<>(events));

            for (int i = 0; i < weight; i++) {
                weightedTenantOrder.add(tenantView.getTenantId());
            }
        }

        if (weightedTenantOrder.isEmpty()) {
            return;
        }

        int publishedThisCycle = 0;
        while (publishedThisCycle < BATCH_SIZE) {
            boolean publishedInPass = false;

            for (UUID tenantId : weightedTenantOrder) {
                List<OutboxEvent> events = tenantEvents.get(tenantId);
                if (events == null || events.isEmpty()) {
                    continue;
                }

                publishOne(events.remove(0));
                publishedThisCycle++;
                publishedInPass = true;

                if (publishedThisCycle >= BATCH_SIZE) {
                    break;
                }
            }

            if (!publishedInPass) {
                break;
            }
        }
    }

    private void publishOne(OutboxEvent event) {
        var notification = event.getNotification();
        MDC.put("notificationId", notification.getId().toString());
        try {
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
                return;
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
            notificationRepository.markQueuedIfAccepted(notification.getId());
            publishedCounter.increment();
            log.info("Published outbox event {} for notification {} via {}",
                    event.getId(), notification.getId(), routingKey);
        } catch (RuntimeException e) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setLastError(e.getMessage());
            outboxEventRepository.save(event);
            log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
        } finally {
            MDC.remove("notificationId");
        }
    }
}
