package com.app.demo.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.app.demo.model.Tenant;
import com.app.demo.repository.TenantRepository;
import com.app.demo.service.TenantQueueLifecycleService;

/**
 * For each tenant at application startup, dynamically creates:
 *   - email-queue.<tenantId>
 *   - webhook-queue.<tenantId>
 * And binds them to the notifications-exchange with tenant-specific routing keys.
 * Also registers listeners for each tenant.
 */

@Component
public class TenantQueueRegistrar {
    private final Logger log = LoggerFactory.getLogger(TenantQueueRegistrar.class);

    private final TenantRepository tenantRepository;
    private final RabbitAdmin rabbitAdmin;
    private final TopicExchange notificationsExchange;
    private final TenantQueueLifecycleService lifecycleService;

    public TenantQueueRegistrar(
            TenantRepository tenantRepository, 
            RabbitAdmin rabbitAdmin, 
            TopicExchange notificationsExchange,
            TenantQueueLifecycleService lifecycleService) {
        this.tenantRepository = tenantRepository;
        this.rabbitAdmin = rabbitAdmin;
        this.notificationsExchange = notificationsExchange;
        this.lifecycleService = lifecycleService;
    }

    // Runs at startup (after beans are created, but before regular listeners)
    @EventListener(ApplicationReadyEvent.class)
    @Order(1) // Ensure this runs before any listeners that might consume from the queues
    public void registerTenantQueues() {
        log.info("Registering per-tenant queues...");
        try {
            List<Tenant> tenants = tenantRepository.findAllByDeletedAtIsNull();
            log.info("Found {} active tenants. Setting up queues for each tenant.", tenants.size());
            for (Tenant tenant : tenants) {
                registerQueuesForTenant(tenant);
                // Also reconcile (register listeners) for each tenant
                lifecycleService.reconcile(tenant);
            }
            log.info("Finished registering tenant queues.");
        } catch (Exception e) {
            log.error("Error registering tenant queues at startup (non-fatal): {}", e.getMessage(), e);
        }
    }

        private void registerQueuesForTenant(Tenant tenant) {
        try {
            String tenantId = tenant.getId().toString();

            // Only create the queues for the channels this tenant actually uses
            if (tenant.isEmailEnabled()) {
            String emailQueueName = "email-queue." + tenantId;
            var emailQueue = QueueBuilder.durable(emailQueueName)
                .withArgument("x-dead-letter-exchange", "dead-letter-exchange")
                .build();
            rabbitAdmin.declareQueue(emailQueue);

            String emailRoutingKey = "notification.email." + tenantId;
            Binding emailBinding = BindingBuilder
                .bind(emailQueue)
                .to(notificationsExchange)
                .with(emailRoutingKey);
            rabbitAdmin.declareBinding(emailBinding);
            log.debug("Registered email queue for tenant {}: {}", tenantId, emailQueueName);
            }

            if (tenant.isWebhookEnabled()) {
            String webhookQueueName = "webhook-queue." + tenantId;
            var webhookQueue = QueueBuilder.durable(webhookQueueName)
                .withArgument("x-dead-letter-exchange", "dead-letter-exchange")
                .build();
            rabbitAdmin.declareQueue(webhookQueue);

            String webhookRoutingKey = "notification.webhook." + tenantId;
            Binding webhookBinding = BindingBuilder
                .bind(webhookQueue)
                .to(notificationsExchange)
                .with(webhookRoutingKey);
            rabbitAdmin.declareBinding(webhookBinding);
            log.debug("Registered webhook queue for tenant {}: {}", tenantId, webhookQueueName);
            }
        } catch (Exception e) {
            log.error("Failed to register queues for tenant {}: {}", tenant.getId(), e.getMessage(), e);
        }
    }
}
