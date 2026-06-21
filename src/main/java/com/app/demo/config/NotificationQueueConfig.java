package com.app.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notification-queues")
public class NotificationQueueConfig {

    private QueueStrategyMode mode = QueueStrategyMode.PER_TENANT;

    public QueueStrategyMode getMode() {
        return mode;
    }

    public void setMode(QueueStrategyMode mode) {
        this.mode = mode;
    }

    public boolean isShared() {
        return mode == QueueStrategyMode.SHARED;
    }

    public boolean isPerTenant() {
        return mode == QueueStrategyMode.PER_TENANT;
    }
}