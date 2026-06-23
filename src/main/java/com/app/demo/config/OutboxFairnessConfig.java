package com.app.demo.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.outbox-fairness")
public class OutboxFairnessConfig {

    private Map<String, Integer> weights = new LinkedHashMap<>();

    public Map<String, Integer> getWeights() {
        return weights;
    }

    public void setWeights(Map<String, Integer> weights) {
        this.weights = weights;
    }

    public int weightFor(UUID tenantId, String tenantName) {
        if (tenantName != null) {
            Integer weight = weights.get(tenantName);
            if (weight != null) {
                return normalize(weight);
            }
        }

        if (tenantId != null) {
            Integer weight = weights.get(tenantId.toString());
            if (weight != null) {
                return normalize(weight);
            }
        }

        return 1;
    }

    private int normalize(Integer weight) {
        if (weight < 1) {
            return 1;
        }
        return weight;
    }
}