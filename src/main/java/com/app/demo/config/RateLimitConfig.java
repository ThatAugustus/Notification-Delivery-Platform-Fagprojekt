package com.app.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;;

@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitConfig {
    private boolean enabled = true;
    private long capacity = 100;
    private long refillTokens = 20;
    private long refillPeriodSeconds = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public long getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(long refillTokens) {
        this.refillTokens = refillTokens;
    }

    public long getRefillPeriodSeconds() {
        return refillPeriodSeconds;
    }

    public void setRefillPeriodSeconds(long refillPeriodSeconds) {
        this.refillPeriodSeconds = refillPeriodSeconds;
    }
}
