package com.app.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

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

    private final RabbitListenerEndpointRegistry endpointRegistry;
    private final RabbitListenerContainerFactory<? extends MessageListenerContainer> rabbitListenerContainerFactory;
    private final EmailWorker emailWorker;
    private final WebhookWorker webhookWorker;
    public DynamicWorkerListenerRegistrar(
            RabbitListenerEndpointRegistry endpointRegistry,
            RabbitListenerContainerFactory<? extends MessageListenerContainer> rabbitListenerContainerFactory,
            EmailWorker emailWorker,
            WebhookWorker webhookWorker) {
        this.endpointRegistry = endpointRegistry;
        this.rabbitListenerContainerFactory = rabbitListenerContainerFactory;
        this.emailWorker = emailWorker;
        this.webhookWorker = webhookWorker;
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
