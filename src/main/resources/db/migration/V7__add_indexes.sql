-- Auth looks this up on every request.
CREATE UNIQUE INDEX idx_api_keys_key_hash
    ON api_keys (key_hash);

-- RetryPoller scans this every 2 seconds.
CREATE INDEX idx_notifications_retry_ready
    ON notifications (next_retry_at)
    WHERE status = 'RETRY_SCHEDULED';

-- Stale-notification sweep runs every 30 seconds.
CREATE INDEX idx_notifications_stale
    ON notifications (updated_at)
    WHERE status IN ('QUEUED', 'PROCESSING');

-- Outbox publisher polls for unpublished rows.
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published = false;

-- Idempotency lookup when Redis misses.
CREATE INDEX idx_notifications_tenant_idem
    ON notifications (tenant_id, idempotency_key);

-- Speeds the cascade delete and any per-notification audit query.
CREATE INDEX idx_delivery_attempts_notification
    ON delivery_attempts (notification_id);

-- Used by the admin list-keys endpoint.
CREATE INDEX idx_api_keys_tenant
    ON api_keys (tenant_id);