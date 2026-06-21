package com.app.demo.config;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.app.demo.model.enums.NotificationChannel;

class NotificationQueueNamingTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void perTenantStrategy_usesTenantSpecificRoutingAndQueueNames() {
        assertThat(NotificationQueueNaming.routingKey(NotificationChannel.EMAIL, TENANT_ID, QueueStrategyMode.PER_TENANT))
                .isEqualTo("notification.email." + TENANT_ID);
        assertThat(NotificationQueueNaming.queueName(NotificationChannel.EMAIL, TENANT_ID, QueueStrategyMode.PER_TENANT))
                .isEqualTo("email-queue." + TENANT_ID);
        assertThat(NotificationQueueNaming.listenerId(NotificationChannel.EMAIL, TENANT_ID, QueueStrategyMode.PER_TENANT))
                .isEqualTo("email-listener-" + TENANT_ID);
    }

    @Test
    void sharedStrategy_usesChannelOnlyRoutingAndSharedQueueNames() {
        assertThat(NotificationQueueNaming.routingKey(NotificationChannel.WEBHOOK, TENANT_ID, QueueStrategyMode.SHARED))
                .isEqualTo("notification.webhook");
        assertThat(NotificationQueueNaming.queueName(NotificationChannel.WEBHOOK, TENANT_ID, QueueStrategyMode.SHARED))
                .isEqualTo("webhook-queue");
        assertThat(NotificationQueueNaming.listenerId(NotificationChannel.WEBHOOK, TENANT_ID, QueueStrategyMode.SHARED))
                .isEqualTo("webhook-listener");
    }
}