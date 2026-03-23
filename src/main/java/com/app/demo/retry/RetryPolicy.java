package com.app.demo.retry;

import java.time.Instant;
import java.util.Random;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {

    private static final long BASE_DELAY_MS  = 5_000; // 5 seconds
    private static final long MAX_DELAY_MS   = 300_000; // 5 minutes

    private final Random random = new Random();

    public Instant calculateNextRetryAt(int retryCount) {
        long exponentialMs = BASE_DELAY_MS * (1L << retryCount); // (1L << retryCount) is 2^retryCount (so it doubles each time)
        long cappedMs = Math.min(exponentialMs, MAX_DELAY_MS); // to prevent to long delays after many retries
        long jitteredMs = (long) (random.nextDouble() * cappedMs); // random delay number will be somewhere between 0 and the capped exponential backoff time.
        return Instant.now().plusMillis(jitteredMs); // add the randomness to prevent a lot of retries happening at the same time. 
    }

    public boolean hasRetriesLeft(int currentRetryCount, int maxRetries) {
        return currentRetryCount < maxRetries;
    }

    public long getBaseDelayMs() { return BASE_DELAY_MS; }
    public long getMaxDelayMs()  { return MAX_DELAY_MS; }
}