package com.app.demo.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.app.demo.model.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByTenant_IdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
    Optional<Notification> findByIdAndTenant_Id(UUID id, UUID tenantId);

    // JPA doesn't support "SKIP LOCKED" with method names, so we write a query ourselves for this one
    // SKIP LOCKED eliminates race conditions. This is why we chose postgres.
    @Query(value = """
        SELECT * FROM notifications
        WHERE status = 'RETRY_SCHEDULED'
            AND next_retry_at <= NOW()
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Notification> findRetryReady(@Param("batchSize") int batchSize);


    @Query(value = """
        SELECT * FROM notifications
        WHERE status IN ('QUEUED', 'PROCESSING')
            AND updated_at < NOW() - CAST(:thresholdMinutes || ' minutes' AS INTERVAL)
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Notification> findStale(@Param("thresholdMinutes") int thresholdMinutes, @Param("batchSize") int batchSize);

    // Only move ACCEPTED -> QUEUED. A worker may already have raced ahead to DELIVERED; don't overwrite it.
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE notifications
        SET status = 'QUEUED', updated_at = NOW()
        WHERE id = :id AND status = 'ACCEPTED'
        """, nativeQuery = true)
    int markQueuedIfAccepted(@Param("id") UUID id);

    // Counts unfinished work for the drain check. The broker's message count can't be used: listeners
    // prefetch, so messages vanish from it while still unprocessed.
    @Query(value = """
        SELECT COUNT(*) FROM notifications
        WHERE tenant_id = :tenantId
            AND channel = :channel
            AND status IN ('ACCEPTED', 'QUEUED', 'PROCESSING', 'RETRY_SCHEDULED')
        """, nativeQuery = true)
    long countInFlightForTenantAndChannel(@Param("tenantId") UUID tenantId, @Param("channel") String channel);

    // Drain deadline hit: fail anything still undelivered so it doesn't linger as a fake QUEUED row.
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE notifications
        SET status = 'FAILED', next_retry_at = NULL, updated_at = NOW()
        WHERE tenant_id = :tenantId
            AND status IN ('ACCEPTED', 'QUEUED', 'PROCESSING', 'RETRY_SCHEDULED')
        """, nativeQuery = true)
    int markUndeliveredAsFailedForTenant(@Param("tenantId") UUID tenantId);
}