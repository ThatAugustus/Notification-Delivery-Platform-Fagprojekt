package com.app.demo.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.demo.model.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByPublishedFalse();

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE published = false
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Query(value = """
        SELECT
            n.tenant_id AS tenantId,
            t.name AS tenantName
        FROM outbox_events o
        JOIN notifications n ON n.id = o.notification_id
        JOIN tenants t ON t.id = n.tenant_id
        WHERE o.published = false
        GROUP BY n.tenant_id, t.name
        ORDER BY MIN(o.created_at) ASC
        LIMIT :tenantLimit
        """, nativeQuery = true)
    List<PendingOutboxTenantView> findPendingTenants(@Param("tenantLimit") int tenantLimit);

    @Query(value = """
        SELECT o.*
        FROM outbox_events o
        JOIN notifications n ON n.id = o.notification_id
        WHERE o.published = false
            AND n.tenant_id = :tenantId
        ORDER BY o.created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatchForTenant(
            @Param("tenantId") UUID tenantId,
            @Param("batchSize") int batchSize);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.published = false AND e.notification.tenant.id = :tenantId")
    long countUnpublishedByTenant(@Param("tenantId") UUID tenantId);
}