package com.app.demo.retry;

import java.time.Instant;
import java.util.Random;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {

    private static final long BASE_DELAY_MS  = 5_000;
    private static final long MAX_DELAY_MS   = 300_000;

    private final Random random = new Random();

    public Instant calculateNextRetryAt(int retryCount) {
        long exponentialMs = BASE_DELAY_MS * (1L << retryCount);
        long cappedMs = Math.min(exponentialMs, MAX_DELAY_MS);
        long jitteredMs = (long) (random.nextDouble() * cappedMs);
        return Instant.now().plusMillis(jitteredMs);
    }

    public boolean hasRetriesLeft(int currentRetryCount, int maxRetries) {
        return currentRetryCount < maxRetries;
    }

    public long getBaseDelayMs() { return BASE_DELAY_MS; }
    public long getMaxDelayMs()  { return MAX_DELAY_MS; }
}