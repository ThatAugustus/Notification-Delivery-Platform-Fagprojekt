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

    // tenant_id is denormalized onto outbox_events (V10), so neither fairness query
    // needs to join notifications — both run off idx_outbox_pending.
    @Query(value = """
        SELECT
            o.tenant_id AS tenantId,
            t.name AS tenantName
        FROM outbox_events o
        JOIN tenants t ON t.id = o.tenant_id
        WHERE o.published = false
        GROUP BY o.tenant_id, t.name
        ORDER BY MIN(o.created_at) ASC
        LIMIT :tenantLimit
        """, nativeQuery = true)
    List<PendingOutboxTenantView> findPendingTenants(@Param("tenantLimit") int tenantLimit);

    @Query(value = """
        SELECT *
        FROM outbox_events
        WHERE published = false
            AND tenant_id = :tenantId
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatchForTenant(
            @Param("tenantId") UUID tenantId,
            @Param("batchSize") int batchSize);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.published = false AND e.notification.tenant.id = :tenantId")
    long countUnpublishedByTenant(@Param("tenantId") UUID tenantId);
}