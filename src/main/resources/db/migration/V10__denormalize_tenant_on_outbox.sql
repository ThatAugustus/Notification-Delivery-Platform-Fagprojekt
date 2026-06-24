-- Denormalize tenant_id onto outbox_events.
--
-- The OutboxPublisher's per-tenant fairness queries (findPendingTenants,
-- findUnpublishedBatchForTenant) previously JOINed the large notifications table
-- just to resolve each event's tenant, forcing a sequential scan of notifications
-- under load. Carrying tenant_id directly on the outbox row lets both queries run
-- off a single small partial index instead.

ALTER TABLE outbox_events ADD COLUMN tenant_id UUID;

-- Backfill existing rows from their notification.
UPDATE outbox_events o
SET tenant_id = n.tenant_id
FROM notifications n
WHERE n.id = o.notification_id;

ALTER TABLE outbox_events ALTER COLUMN tenant_id SET NOT NULL;

-- One partial index serves both fairness queries:
--   findUnpublishedBatchForTenant: WHERE published=false AND tenant_id=? ORDER BY created_at
--   findPendingTenants:            WHERE published=false GROUP BY tenant_id ORDER BY MIN(created_at)
-- It only holds unpublished rows (typically hundreds), pre-sorted by (tenant_id, created_at).
CREATE INDEX idx_outbox_pending
    ON outbox_events (tenant_id, created_at)
    WHERE published = false;
