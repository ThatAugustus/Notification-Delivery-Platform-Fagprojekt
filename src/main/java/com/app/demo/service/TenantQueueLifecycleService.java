package com.app.demo.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.app.demo.config.NotificationQueueConfig;
import com.app.demo.config.NotificationQueueNaming;
import com.app.demo.config.QueueStrategyMode;
import com.app.demo.model.Tenant;
import com.app.demo.model.enums.NotificationChannel;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import com.app.demo.repository.TenantRepository;
import com.app.demo.worker.EmailWorker;
import com.app.demo.worker.WebhookWorker;

@Service
public class TenantQueueLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(TenantQueueLifecycleService.class);

    private final RabbitAdmin rabbitAdmin;
    private final TopicExchange notificationsExchange;
    private final RabbitListenerEndpointRegistry endpointRegistry;
    private final RabbitListenerContainerFactory<? extends MessageListenerContainer> containerFactory;
    private final TenantRepository tenantRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final EmailWorker emailWorker;
    private final WebhookWorker webhookWorker;
    private final NotificationQueueConfig notificationQueueConfig;

    // how long we let a deleted tenant finish its backlog before we just remove the queues and fail the rest
    @Value("${app.tenant-drain.grace-seconds:120}")
    private long drainGraceSeconds;

    public TenantQueueLifecycleService(
            RabbitAdmin rabbitAdmin,
            TopicExchange notificationsExchange,
            RabbitListenerEndpointRegistry endpointRegistry,
            RabbitListenerContainerFactory<? extends MessageListenerContainer> containerFactory,
            TenantRepository tenantRepository,
            OutboxEventRepository outboxEventRepository,
            NotificationRepository notificationRepository,
            EmailWorker emailWorker,
            WebhookWorker webhookWorker,
            NotificationQueueConfig notificationQueueConfig) {
        this.rabbitAdmin = rabbitAdmin;
        this.notificationsExchange = notificationsExchange;
        this.endpointRegistry = endpointRegistry;
        this.containerFactory = containerFactory;
        this.tenantRepository = tenantRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.emailWorker = emailWorker;
        this.webhookWorker = webhookWorker;
        this.notificationQueueConfig = notificationQueueConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void reconcileExistingTenants() {
        if (notificationQueueConfig.isShared()) {
            List<Tenant> tenants = tenantRepository.findAllByDeletedAtIsNull();
            log.info("Switching to shared notification queues; removing tenant-specific queues for {} active tenants", tenants.size());
            for (Tenant tenant : tenants) {
                removeTenantResources(tenant.getId().toString());
            }
            ensureSharedTopology();
            return;
        }

        List<Tenant> tenants = tenantRepository.findAllByDeletedAtIsNull();
        log.info("Reconciling {} active tenants at startup", tenants.size());
        for (Tenant tenant : tenants) {
            reconcile(tenant);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantLifecycleEvent(TenantLifecycleEvent event) {
        try {
            Tenant tenant = event.getTenant();
            log.info("Received tenant lifecycle event {} for tenant {}", event.getAction(), tenant.getId());
            reconcile(tenant);
        } catch (Exception e) {
            log.error("Error handling TenantLifecycleEvent: {}", e.getMessage(), e);
        }
    }

    // Reconcile desired state for a single tenant: create or remove queues/listeners as needed
    public void reconcile(Tenant tenant) {
        if (notificationQueueConfig.isShared()) {
            ensureSharedTopology();
            return;
        }

        if (tenant.isDeleted()) {
            // don't remove the queues yet, let them finish delivering what's already in them.
            // drainDeletedTenants() does the cleanup once they're empty
            log.info("Tenant {} soft-deleted; draining its queues before teardown", tenant.getId());
            return;
        }

        if (tenant.isEmailEnabled()) {
            ensureQueueAndListener(
                    NotificationQueueNaming.queueName(NotificationChannel.EMAIL, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.listenerId(NotificationChannel.EMAIL, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.routingKey(NotificationChannel.EMAIL, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    (msg) -> emailWorker.listen(msg));
        } else {
            removeQueueAndListener(
                    NotificationQueueNaming.queueName(NotificationChannel.EMAIL, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.listenerId(NotificationChannel.EMAIL, tenant.getId(), QueueStrategyMode.PER_TENANT));
        }

        if (tenant.isWebhookEnabled()) {
            ensureQueueAndListener(
                    NotificationQueueNaming.queueName(NotificationChannel.WEBHOOK, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.listenerId(NotificationChannel.WEBHOOK, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.routingKey(NotificationChannel.WEBHOOK, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    (msg) -> webhookWorker.listen(msg));
        } else {
            removeQueueAndListener(
                    NotificationQueueNaming.queueName(NotificationChannel.WEBHOOK, tenant.getId(), QueueStrategyMode.PER_TENANT),
                    NotificationQueueNaming.listenerId(NotificationChannel.WEBHOOK, tenant.getId(), QueueStrategyMode.PER_TENANT));
        }
    }

    public String routingKeyFor(NotificationChannel channel, java.util.UUID tenantId) {
        return NotificationQueueNaming.routingKey(channel, tenantId, notificationQueueConfig.getMode());
    }

    public boolean destinationQueueExists(NotificationChannel channel, UUID tenantId) {
        if (notificationQueueConfig.isShared()) {
            return true;
        }
        String queueName = NotificationQueueNaming.queueName(channel, tenantId, QueueStrategyMode.PER_TENANT);
        return rabbitAdmin.getQueueProperties(queueName) != null;
    }

    @Scheduled(fixedDelayString = "${app.tenant-drain.poll-ms:1000}")
    public void drainDeletedTenants() {
        if (notificationQueueConfig.isShared()) {
            return; // per-tenant queues only
        }
        for (Tenant tenant : tenantRepository.findAllByDeletedAtIsNotNull()) {
            drainChannel(tenant, NotificationChannel.EMAIL);
            drainChannel(tenant, NotificationChannel.WEBHOOK);
        }
    }

    private void drainChannel(Tenant tenant, NotificationChannel channel) {
        String queueName = NotificationQueueNaming.queueName(channel, tenant.getId(), QueueStrategyMode.PER_TENANT);
        String listenerId = NotificationQueueNaming.listenerId(channel, tenant.getId(), QueueStrategyMode.PER_TENANT);

        if (rabbitAdmin.getQueueProperties(queueName) == null) {
            return; // already gone
        }

        // safe to remove once nothing's in flight and the outbox has nothing left to send
        boolean inFlight = notificationRepository.countInFlightForTenantAndChannel(
                tenant.getId(), channel.name()) > 0;
        boolean outboxPending = outboxEventRepository.countUnpublishedByTenant(tenant.getId()) > 0;

        if (!inFlight && !outboxPending) {
            removeQueueAndListener(queueName, listenerId);
            log.info("Drained and removed {} for soft-deleted tenant {}", queueName, tenant.getId());
            return;
        }

        if (drainDeadlinePassed(tenant)) {
            int failed = notificationRepository.markUndeliveredAsFailedForTenant(tenant.getId());
            removeQueueAndListener(queueName, listenerId);
            log.warn("Drain deadline exceeded for tenant {}; force-removed {} and marked {} notification(s) FAILED",
                    tenant.getId(), queueName, failed);
        }
    }

    private boolean drainDeadlinePassed(Tenant tenant) {
        Instant deletedAt = tenant.getDeletedAt();
        return deletedAt != null && Instant.now().isAfter(deletedAt.plusSeconds(drainGraceSeconds));
    }

    private void ensureSharedTopology() {
        ensureQueueAndListener(
                NotificationQueueNaming.queueName(NotificationChannel.EMAIL, null, QueueStrategyMode.SHARED),
                NotificationQueueNaming.listenerId(NotificationChannel.EMAIL, null, QueueStrategyMode.SHARED),
                NotificationQueueNaming.routingKey(NotificationChannel.EMAIL, null, QueueStrategyMode.SHARED),
                (msg) -> emailWorker.listen(msg));

        ensureQueueAndListener(
                NotificationQueueNaming.queueName(NotificationChannel.WEBHOOK, null, QueueStrategyMode.SHARED),
                NotificationQueueNaming.listenerId(NotificationChannel.WEBHOOK, null, QueueStrategyMode.SHARED),
                NotificationQueueNaming.routingKey(NotificationChannel.WEBHOOK, null, QueueStrategyMode.SHARED),
                (msg) -> webhookWorker.listen(msg));
    }

    private void ensureQueueAndListener(String queueName, String listenerId, String routingKey, java.util.function.Consumer<Message> handler) {
        try {
            // Declare queue if missing
            var queue = QueueBuilder.durable(queueName)
                    .withArgument("x-dead-letter-exchange", "dead-letter-exchange")
                    .build();
            rabbitAdmin.declareQueue(queue);

            Binding binding = BindingBuilder.bind(queue).to(notificationsExchange).with(routingKey);
            rabbitAdmin.declareBinding(binding);

            // Register listener if missing
            if (endpointRegistry.getListenerContainer(listenerId) == null) {
                SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
                endpoint.setId(listenerId);
                endpoint.setQueueNames(queueName);
                endpoint.setMessageListener(message -> handler.accept((Message) message));
                endpointRegistry.registerListenerContainer(endpoint, containerFactory, true);
                log.info("Registered listener {} for queue {}", listenerId, queueName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure queue/listener {} / {}: {}", queueName, listenerId, e.getMessage(), e);
        }
    }

    private void removeTenantResources(String tenantId) {
        removeQueueAndListener("email-queue." + tenantId, "email-listener-" + tenantId);
        removeQueueAndListener("webhook-queue." + tenantId, "webhook-listener-" + tenantId);
    }

    private void removeQueueAndListener(String queueName, String listenerId) {
        try {
            var container = endpointRegistry.getListenerContainer(listenerId);
            if (container != null) {
                container.stop();
                log.info("Stopped listener {}", listenerId);
            }
            rabbitAdmin.deleteQueue(queueName);
            log.info("Deleted queue {}", queueName);
        } catch (Exception e) {
            log.error("Failed to remove queue/listener {} / {}: {}", queueName, listenerId, e.getMessage(), e);
        }
    }
}
