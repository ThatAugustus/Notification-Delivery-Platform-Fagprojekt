package com.app.demo.service;

import java.util.List;

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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.app.demo.model.Tenant;
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
    private final EmailWorker emailWorker;
    private final WebhookWorker webhookWorker;
    
    public TenantQueueLifecycleService(
            RabbitAdmin rabbitAdmin,
            TopicExchange notificationsExchange,
            RabbitListenerEndpointRegistry endpointRegistry,
            RabbitListenerContainerFactory<? extends MessageListenerContainer> containerFactory,
            TenantRepository tenantRepository,
            EmailWorker emailWorker,
            WebhookWorker webhookWorker) {
        this.rabbitAdmin = rabbitAdmin;
        this.notificationsExchange = notificationsExchange;
        this.endpointRegistry = endpointRegistry;
        this.containerFactory = containerFactory;
        this.tenantRepository = tenantRepository;
        this.emailWorker = emailWorker;
        this.webhookWorker = webhookWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void reconcileExistingTenants() {
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
        String tenantId = tenant.getId().toString();

        if (tenant.isDeleted()) {
            removeTenantResources(tenantId);
            return;
        }

        if (tenant.isEmailEnabled()) {
            ensureQueueAndListener("email-queue." + tenantId, "email-listener-" + tenantId,
                    "notification.email." + tenantId, (msg) -> emailWorker.listen(msg));
        } else {
            removeQueueAndListener("email-queue." + tenantId, "email-listener-" + tenantId);
        }

        if (tenant.isWebhookEnabled()) {
            ensureQueueAndListener("webhook-queue." + tenantId, "webhook-listener-" + tenantId,
                    "notification.webhook." + tenantId, (msg) -> webhookWorker.listen(msg));
        } else {
            removeQueueAndListener("webhook-queue." + tenantId, "webhook-listener-" + tenantId);
        }
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
