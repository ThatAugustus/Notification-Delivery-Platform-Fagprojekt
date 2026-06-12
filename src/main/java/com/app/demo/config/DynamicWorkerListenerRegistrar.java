package com.app.demo.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.demo.model.Tenant;
import com.app.demo.repository.TenantRepository;
import com.app.demo.worker.EmailWorker;
import com.app.demo.worker.WebhookWorker;

/**
 * For each per-tenant queue at application startup, dynamically registers listeners:
 *   - EmailWorker listening to email-queue.<tenantId>
 *   - WebhookWorker listening to webhook-queue.<tenantId>
 * Workers consume from all tenant queues - FIFO per queue.
 */

@Component
public class DynamicWorkerListenerRegistrar {
    private static final Logger log = LoggerFactory.getLogger(DynamicWorkerListenerRegistrar.class);

    private final TenantRepository tenantRepository;
    private final RabbitListenerEndpointRegistry endpointRegistry;
    private final RabbitListenerContainerFactory<? extends MessageListenerContainer> rabbitListenerContainerFactory;
    private final EmailWorker emailWorker;
    private final WebhookWorker webhookWorker;

    public DynamicWorkerListenerRegistrar(
            TenantRepository tenantRepository,
            RabbitListenerEndpointRegistry endpointRegistry,
            RabbitListenerContainerFactory<? extends MessageListenerContainer> rabbitListenerContainerFactory,
            EmailWorker emailWorker,
            WebhookWorker webhookWorker) {
        this.tenantRepository = tenantRepository;
        this.endpointRegistry = endpointRegistry;
        this.rabbitListenerContainerFactory = rabbitListenerContainerFactory;
        this.emailWorker = emailWorker;
        this.webhookWorker = webhookWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWorkerListeners() {
        log.info("Registering dynamic worker listeners for all tenant queues...");

        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Found {} tenants. Setting up listeners for each tenant's queues.", tenants.size());

        for (Tenant tenant : tenants) {
            String tenantId = tenant.getId().toString();
            registerListener("email-queue." + tenantId, "email-listener-" + tenantId, emailWorker::listen);
            registerListener("webhook-queue." + tenantId, "webhook-listener-" + tenantId, webhookWorker::listen);
        }
        log.info("Finished registering worker listeners.");
    }

    private void registerListener(String queueName, String listenerId, MessageHandler handler) {
        if (endpointRegistry.getListenerContainer(listenerId) != null) {
            log.debug("Listener {} already exists, skipping", listenerId);
            return;
        }

        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(listenerId);
        endpoint.setQueueNames(queueName);
        endpoint.setMessageListener(message -> handler.handleMessage((Message) message));

        endpointRegistry.registerListenerContainer(endpoint, rabbitListenerContainerFactory, true);
        log.info("Registered listener {} for queue {}", listenerId, queueName);
    }

    @FunctionalInterface
    private interface MessageHandler {
        void handleMessage(Message message);
    }
}
