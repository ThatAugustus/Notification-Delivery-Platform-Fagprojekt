package com.app.demo.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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
    @Order(2) // run after TenantQueueRegistrar to ensure queues are created before listeners
    public void registerWorkerListeners() {
        log.info("Registering dynamic worker listeners for all tenant queues...");
        try {
            List<Tenant> tenants = tenantRepository.findAll();
            log.info("Found {} tenants. Setting up listeners for each tenant's queues.", tenants.size());

            for (Tenant tenant : tenants) {
                String tenantId = tenant.getId().toString();
                try {
                    registerListener("email-queue." + tenantId, "email-listener-" + tenantId, emailWorker::listen);
                } catch (Exception e) {
                    log.error("Failed to register email listener for tenant {} (non-fatal): {}", tenantId, e.getMessage(), e);
                }
                try {
                    registerListener("webhook-queue." + tenantId, "webhook-listener-" + tenantId, webhookWorker::listen);
                } catch (Exception e) {
                    log.error("Failed to register webhook listener for tenant {} (non-fatal): {}", tenantId, e.getMessage(), e);
                }
            }
            log.info("Finished registering worker listeners.");
        } catch (Exception e) {
            log.error("Error registering worker listeners at startup (non-fatal): {}", e.getMessage(), e);
        }
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

        try {
            endpointRegistry.registerListenerContainer(endpoint, rabbitListenerContainerFactory, true);
            log.info("Registered listener {} for queue {}", listenerId, queueName);
        } catch (Exception e) {
            log.error("Failed to register listener {} for queue {}: {}", listenerId, queueName, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface MessageHandler {
        void handleMessage(Message message);
    }
}
