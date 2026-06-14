package com.app.demo.service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.app.demo.config.RateLimitConfig;
import com.app.demo.exception.RateLimitExceededException;

@Service
public class TenantRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiterService.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitConfig rateLimitConfig;

    private static final String TOKEN_BUCKET_SCRIPT = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refill_tokens = tonumber(ARGV[2])
        local refill_period_ms = tonumber(ARGV[3])
        local now_ms = tonumber(ARGV[4])

        local state = redis.call('GET', key)
        local tokens, last_refill_ms

        if state == false then
            tokens = capacity
            last_refill_ms = now_ms
        else
            local sep = ','
            local idx = string.find(state, sep)
            tokens = tonumber(string.sub(state, 1, idx - 1))
            last_refill_ms = tonumber(string.sub(state, idx + 1))
            local elapsed_ms = now_ms - last_refill_ms
            local refill_count = math.floor(elapsed_ms / refill_period_ms)
            if refill_count > 0 then
                local new_tokens = tokens + (refill_count * refill_tokens)
                if new_tokens > capacity then
                    new_tokens = capacity
                end
                tokens = new_tokens
                last_refill_ms = last_refill_ms + (refill_count * refill_period_ms)
            end
        end

        if tokens >= 1 then
            tokens = tokens - 1
            redis.call('SET', key, tokens .. ',' .. last_refill_ms, 'EX', '3600')
            return {1, 0}
        else
            local retry_after_ms = refill_period_ms - (now_ms - last_refill_ms)
            local retry_after_sec = math.ceil(retry_after_ms / 1000)
            return {0, retry_after_sec}
        end
        """;

    public TenantRateLimiterService(StringRedisTemplate redisTemplate, RateLimitConfig rateLimitConfig) {
        this.redisTemplate = redisTemplate;
        this.rateLimitConfig = rateLimitConfig;
    }

    public void assertAllowed(UUID tenantId) {
        if (!rateLimitConfig.isEnabled()) {
            return;
        }

        String key = "ratelimit:tenant:" + tenantId.toString();
        long now = System.currentTimeMillis();

        try {
            RedisScript<List> script = RedisScript.of(TOKEN_BUCKET_SCRIPT, List.class);
            List<?> result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(rateLimitConfig.getCapacity()),
                    String.valueOf(rateLimitConfig.getRefillTokens()),
                    String.valueOf(rateLimitConfig.getRefillPeriodSeconds() * 1000),
                    String.valueOf(now)
            );

            if (result == null || result.size() < 2) {
                log.warn("Rate limiter script returned unexpected result, allowing request");
                return;
            }

            long allowed = Long.parseLong(result.get(0).toString());
            long retryAfter = Long.parseLong(result.get(1).toString());

            if (allowed == 1L) {
                log.debug("Tenant {} allowed (tokens remaining)", tenantId);
                return;
            } else {
                int retryAfterSecs = (int) Math.max(1, retryAfter);
                log.warn("Tenant {} rate limited, retry after {}s", tenantId, retryAfterSecs);
                throw new RateLimitExceededException("Rate limit exceeded for tenant " + tenantId, retryAfterSecs);
            }
        } catch (RateLimitExceededException ex) {
            throw ex; // rethrow our known exception as-is
        } catch (Exception ex) {
            log.error("Rate limiter error for tenant {}: {}", tenantId, ex.getMessage(), ex);
            log.warn("Failing open: allowing request due to rate limiter failure");
        }
    }
}
