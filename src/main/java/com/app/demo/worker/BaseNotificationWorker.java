package com.app.demo.worker;

import java.time.Instant;

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

    public BaseNotificationWorker(
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RetryPolicy retryPolicy) {
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.retryPolicy = retryPolicy;
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

            // 2. Fetch Notification + Mark PROCESSING
            final String notifId = payload.getNotificationId().toString();
            notification = notificationRepository.findById(payload.getNotificationId())
                    .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notifId));
            
            notification.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(notification);

            // 3. Delegate the actual delivery to the specific channel worker
            deliver(payload, notification);

            // 4. Success, Record it
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

            log.info("Notification delivered successfully in {}ms to {}", duration, payload.getRecipient());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Failed to send notification", e);

            // 5. Failure — Record it
            if (notification != null) {

                notification.setRetryCount(notification.getRetryCount() + 1);

                // if any retry left then schedule a retry, otherwise mark as permanently failed
                if (retryPolicy.hasRetriesLeft(notification.getRetryCount(), notification.getMaxRetries())) {
                    // Schedule a retry and then the RetryPoller will pick this up later
                    Instant nextRetry = retryPolicy.calculateNextRetryAt(notification.getRetryCount() - 1); // -1 because we already incremented the retry count above. We want to start with the initial delay for the first retry, not the second.
                    notification.setStatus(NotificationStatus.RETRY_SCHEDULED);
                    notification.setNextRetryAt(nextRetry);

                    log.warn("Scheduling retry #{} for notification {} at {}",
                            notification.getRetryCount(), notification.getId(), nextRetry);
                } else {
                    // No retries left, mark this notification as permanently failed
                    notification.setStatus(NotificationStatus.FAILED);
                    notification.setNextRetryAt(null);

                    log.error("Notification {} permanently FAILED after {} attempts",
                            notification.getId(), notification.getRetryCount());
                }

                notificationRepository.save(notification);
                DeliveryAttempt attempt = new DeliveryAttempt();
                attempt.setNotification(notification);
                attempt.setAttemptNumber(notification.getRetryCount());
                attempt.setStatus(DeliveryAttemptStatus.FAILED);
                attempt.setErrorMessage(e.getMessage());
                attempt.setDurationMs(duration);

                deliveryAttemptRepository.save(attempt);
            }
                if (notification == null) {
                    log.error("Unrecoverable message rejected to DLQ: {}", e.getMessage());
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
