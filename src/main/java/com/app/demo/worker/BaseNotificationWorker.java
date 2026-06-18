package com.app.demo.worker;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.model.DeliveryAttempt;
import com.app.demo.model.Notification;
import com.app.demo.model.enums.DeliveryAttemptStatus;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.DeliveryAttemptRepository;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;


public abstract class BaseNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(BaseNotificationWorker.class);

    protected final ObjectMapper objectMapper;
    protected final NotificationRepository notificationRepository;
    protected final DeliveryAttemptRepository deliveryAttemptRepository;
    protected final RetryPolicy retryPolicy;
    protected final MeterRegistry meterRegistry;

    public BaseNotificationWorker(
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RetryPolicy retryPolicy,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.retryPolicy = retryPolicy;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The template method that handles all the database and error-handling boilerplate.
     * Child classes should call this method from their @RabbitListener methods.
     */
    protected void processMessage(Message message) { 
        long startTime = System.currentTimeMillis();
        NotificationPayload payload = null;
        Notification notification = null;

        try {
            // 1. Parse JSON from RabbitMQ
            String body = new String(message.getBody());
            payload = objectMapper.readValue(body, NotificationPayload.class);
            log.info("Worker received request for notification: {}", payload.getNotificationId());

            // Metrics: count every message received (fires BEFORE processing)
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            String channelTag = "unknown";
            if (routingKey != null && routingKey.startsWith("notification.")) {
                String[] parts = routingKey.split("\\.");
                if (parts.length >= 2) {
                    channelTag = parts[1]; // email or webhook
                }
            }
            String tenantTag = payload.getTenantId() != null ? payload.getTenantId().toString() : "unknown";
            Counter.builder("worker.received")
                    .tag("channel", channelTag)
                    .tag("tenant", tenantTag)
                    .description("Messages received by workers (before processing)")
                    .register(meterRegistry).increment();


            

            // 2. Fetch Notification
            final String notifId = payload.getNotificationId().toString();
            notification = notificationRepository.findById(payload.getNotificationId())
                    .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notifId));

            // 3. check for successful delivery attempts
            if (deliveryAttemptRepository.existsByNotification_IdAndStatus(payload.getNotificationId(), DeliveryAttemptStatus.SUCCESS)) {
                log.warn("Notification already successfully delivered: {}", payload.getNotificationId());
                Counter.builder("worker.processed")
                        .tag("channel", channelTag)
                        .tag("result", "already_delivered")
                        .description("Messages already successfully delivered by workers")
                        .register(meterRegistry).increment();

                notification.setStatus(NotificationStatus.DELIVERED);
                notificationRepository.save(notification);
                return;

            }
            // 4. Mark the notification as processing
            notification.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(notification);

            // 5. Delegate the actual delivery to the specific channel worker
            deliver(payload, notification);

            // 6. Success, Record it
            long duration = System.currentTimeMillis() - startTime;
            notification.setStatus(NotificationStatus.DELIVERED);
            notificationRepository.save(notification);

            DeliveryAttempt attempt = new DeliveryAttempt();
            attempt.setNotification(notification);
            attempt.setAttemptNumber(notification.getRetryCount() + 1);
            attempt.setStatus(DeliveryAttemptStatus.SUCCESS);
            attempt.setErrorMessage(null);
            attempt.setDurationMs(duration);

            deliveryAttemptRepository.save(attempt);

            // Metrics: count success + record duration
            String channel = notification.getChannel().name().toLowerCase();
            String tenantId = notification.getTenant().getId().toString();
            Counter.builder("worker.processed")
                    .tag("channel", channel)
                    .tag("result", "success")
                    .description("Messages successfully delivered by workers")
                    .register(meterRegistry).increment();

            Timer.builder("worker.duration")
                    .tag("channel", channel)
                    .tag("tenant", tenantId)
                    .description("Time to process a message")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);

            log.info("Notification delivered successfully in {}ms to {}", duration, payload.getRecipient());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Failed to send notification", e);

            // Derive error reason for metrics tagging
            String errorReason = e.getClass().getSimpleName();

            // 5. Failure — Record it
            if (notification != null) { // case when notification is not null, i.e. the notification was found
                String channel = notification.getChannel().name().toLowerCase();
                String tenantId = notification.getTenant().getId().toString();

                notification.setRetryCount(notification.getRetryCount() + 1);

                // if any retry left then schedule a retry, otherwise mark as permanently failed
                if (retryPolicy.hasRetriesLeft(notification.getRetryCount(), notification.getMaxRetries())) {
                    // Schedule a retry and then the RetryPoller will pick this up later
                    Instant nextRetry = retryPolicy.calculateNextRetryAt(notification.getRetryCount() - 1); // -1 because we already incremented the retry count above. We want to start with the initial delay for the first retry, not the second.
                    notification.setStatus(NotificationStatus.RETRY_SCHEDULED);
                    notification.setNextRetryAt(nextRetry);

                    log.warn("Scheduling retry #{} for notification {} at {}",
                            notification.getRetryCount(), notification.getId(), nextRetry);

                    // Metrics: count retryable failure
                    Counter.builder("worker.processed")
                            .tag("channel", channel)
                            .tag("result", "retry_scheduled")
                            .tag("tenant", tenantId)
                            .description("Messages that failed and were scheduled for retry")
                            .register(meterRegistry).increment();
                } else {
                    // No retries left, mark this notification as permanently failed
                    notification.setStatus(NotificationStatus.FAILED);
                    notification.setNextRetryAt(null);

                    log.error("Notification {} permanently FAILED after {} attempts",
                            notification.getId(), notification.getRetryCount());

                    // Metrics: count permanent failure
                    Counter.builder("worker.processed")
                            .tag("channel", channel)
                            .tag("result", "failed")
                            .tag("tenant", tenantId)
                            .description("Messages that permanently failed")
                            .register(meterRegistry).increment();
                }

                // Metrics: count failure with error reason
                Counter.builder("worker.errors")
                        .tag("channel", channel)
                        .tag("error", errorReason)
                        .tag("tenant", tenantId)
                        .description("Worker errors by exception type")
                        .register(meterRegistry).increment();

                notificationRepository.save(notification);
                DeliveryAttempt attempt = new DeliveryAttempt();
                attempt.setNotification(notification);
                attempt.setAttemptNumber(notification.getRetryCount());
                attempt.setStatus(DeliveryAttemptStatus.FAILED);
                attempt.setErrorMessage(e.getMessage());
                attempt.setDurationMs(duration);

                deliveryAttemptRepository.save(attempt);
            } else { // case when notification is null, i.e. the notification was not found
                    log.error("Unrecoverable message rejected to DLQ: {}", e.getMessage());
                    Counter.builder("worker.errors")
                            .tag("channel", "unknown")
                            .tag("error", errorReason)
                            .register(meterRegistry).increment();
                    throw new AmqpRejectAndDontRequeueException("Unrecoverable: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Abstract method that concrete subclasses MUST implement to define 
     * exactly how the notification is delivered for their specific channel.
     * 
     * @param payload      The deserialized JSON metadata from RabbitMQ
     * @param notification The actual PostgreSQL entity record
     * @throws Exception   if the delivery mechanism fails (caught and logged as FAILED by the base worker)
     */
    protected abstract void deliver(NotificationPayload payload, Notification notification) throws Exception;
}
