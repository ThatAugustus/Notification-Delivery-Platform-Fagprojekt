package com.app.demo.retry;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.model.Notification;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;

@Component
public class StaleNotificationCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleNotificationCleanup.class);
    private static final int STALE_THRESHOLD_MINUTES = 5;
    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;

    public StaleNotificationCleanup(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Scheduled(fixedDelay = 30_000) // every 30 seconds
    @Transactional
    public void cleanupStaleNotifications() {
        List<Notification> stale = notificationRepository.findStale(STALE_THRESHOLD_MINUTES, BATCH_SIZE);

        if (stale.isEmpty()) {
            return;
        }

        log.warn("Found {} stale notifications stuck in QUEUED/PROCESSING", stale.size());

        for (Notification notification : stale) {
            String previousStatus = notification.getStatus().name();

            notification.setStatus(NotificationStatus.RETRY_SCHEDULED);
            notification.setNextRetryAt(Instant.now()); // due immediately — RetryPoller will pick it up
            notificationRepository.save(notification);

            log.warn("Recovered stale notification {} (was {} for >{}min) → RETRY_SCHEDULED",
                    notification.getId(), previousStatus, STALE_THRESHOLD_MINUTES);
        }
    }
}