package com.app.demo.config;

import java.util.UUID;

import com.app.demo.model.enums.NotificationChannel;

public final class NotificationQueueNaming {

    private static final String PREFIX = "notification.";

    private NotificationQueueNaming() {
    }

    public static String routingKey(NotificationChannel channel, UUID tenantId, QueueStrategyMode mode) {
        String channelName = channel.name().toLowerCase();
        if (mode == QueueStrategyMode.SHARED) {
            return PREFIX + channelName;
        }

        return PREFIX + channelName + "." + tenantId;
    }

    public static String queueName(NotificationChannel channel, UUID tenantId, QueueStrategyMode mode) {
        String channelName = channel.name().toLowerCase();
        if (mode == QueueStrategyMode.SHARED) {
            return channelName + "-queue";
        }

        return channelName + "-queue." + tenantId;
    }

    public static String listenerId(NotificationChannel channel, UUID tenantId, QueueStrategyMode mode) {
        String channelName = channel.name().toLowerCase();
        if (mode == QueueStrategyMode.SHARED) {
            return channelName + "-listener";
        }

        return channelName + "-listener-" + tenantId;
    }
}