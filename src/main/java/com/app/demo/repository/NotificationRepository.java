package com.app.demo.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.demo.model.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByTenant_IdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
    Optional<Notification> findByIdAndTenant_Id(UUID id, UUID tenantId);

    @Query(value = """
        SELECT * FROM notifications
        WHERE status = 'RETRY_SCHEDULED'
            AND next_retry_at <= NOW()
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Notification> findRetryReady(@Param("batchSize") int batchSize);
}