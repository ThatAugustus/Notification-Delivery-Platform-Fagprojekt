package com.app.demo.service;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);
    // How long we remember an idempotency key (24 hours)
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public RedisIdempotencyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }


     // Check if we've already seen this (tenantId + idempotencyKey) combination.
     // Returns the existing notification ID if found, null if not
    public UUID get(UUID tenantId, String idempotencyKey) {
        try {
            String key = buildKey(tenantId, idempotencyKey);
            String value = redis.opsForValue().get(key);
            if (value != null) {
                log.debug("Redis idempotency HIT: key={}", key);
                return UUID.fromString(value);
            }
            return null;
        } catch (Exception e) {
            // If Redis is down, return null, we'll fall back to PostgreSQL
            log.warn("Redis GET failed, falling back to database: {}", e.getMessage());
            return null;
        }
    }


     // Store a (tenantId + idempotencyKey), notificationId mapping.
     // Expires after 24 hours so Redis doesn't grow forever.

    public void put(UUID tenantId, String idempotencyKey, UUID notificationId) {
        try {
            String key = buildKey(tenantId, idempotencyKey);
            redis.opsForValue().set(key, notificationId.toString(), TTL);
            log.debug("Redis idempotency STORE: key={}", key);
        } catch (Exception e) {
            // If Redis is down, just log it, the system still works without it
            log.warn("Redis SET failed, continuing without cache: {}", e.getMessage());
        }
    }


     // Key format: "idempotency:{tenantId}:{idempotencyKey}"

    private String buildKey(UUID tenantId, String idempotencyKey) {
        return "idempotency:" + tenantId + ":" + idempotencyKey;
    }
}